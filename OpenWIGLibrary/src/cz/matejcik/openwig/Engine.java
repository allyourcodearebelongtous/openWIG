/* 
 * Copyright (C) 2014 matejcik
 *
 * This program is covered by the GNU General Public License version 3 or any later version.
 * You can find the full license text at <http://www.gnu.org/licenses/gpl-3.0.html>.
 * No express or implied warranty of any kind is provided.
 */
package cz.matejcik.openwig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Calendar;

import cz.matejcik.openwig.formats.CartridgeFile;
import cz.matejcik.openwig.formats.Savegame;
import cz.matejcik.openwig.platform.LocationService;
import cz.matejcik.openwig.platform.UI;
import se.krka.kahlua.cldc11.CLDC11Platform;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.KahluaThread;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Platform;
import se.krka.kahlua.vm.Prototype;
import util.BackgroundRunner;


/** The OpenWIG Engine
 * <p>
 * This is the heart of OpenWIG. It instantiates the Lua machine and acts
 * as an interface between GPS position source, GUI and the Lua Wherigo script.
 * <p>
 * Engine is a partial singleton - although its singleness is not guarded, it
 * doesn't make sense to run more than one Engine at once, because most components
 * access Engine.instance statically (this is more a convenience than a purposeful
 * decision - it would be massively impractical to have reference to Engine in
 * every last component that might somehow use it).
 * <p>
 * To create a new Engine, you need a CartridgeFile, a reference to UI and LocationService.
 * Optionally, you can provide an OutputStream that will be used for logging.
 * When you get an instance, you can start it via start() for new game, or resume() for
 * continuing a saved game. Note that resume() will fail if there is no saved game.
 * <p>
 * Engine runs in a separate thread, and creates one more utility thread for itself,
 * whose sole purpose is to do everything related to Lua state - calling events, callbacks,
 * saving game.
 * Engine's own main loop consists of relaying position information from LocationService
 * to the Lua properties and evaluating position of player against zones.
 */
public class Engine implements Runnable {

	public static final String VERSION = "428";
	public static final int LOG_PROP = 0;
	public static final int LOG_CALL = 1;
	public static final int LOG_WARN = 2;
	public static final int LOG_ERROR = 3;
	/** the main instance */
	public static Engine instance;
	/** Lua platform - we might want to implement our version */
	public static Platform platform = CLDC11Platform.getInstance();
	/** Lua environment */
	public static KahluaTable environment;
	/** Lua state - don't touch this if you don't have to */
	public static KahluaThread vmThread;
	/** reference to UI implementation */
	protected static UI ui;
	/** reference to LocationService */
	protected static LocationService gps;
	/** Cartridge (a global Lua object) */
	public Cartridge cartridge;
	/** global Player Lua object */
	public Player player = new Player();
	/** reference to source file */
	protected CartridgeFile gwcfile;
	/** reference to top-level source closure */
	protected LuaClosure gwcclosure;
	/** reference to save file */
	protected Savegame savegame = null;
	/** event runner taking care of Lua state calls */
	protected BackgroundRunner eventRunner;
	/** reference to log stream */
	private PrintStream log;
	private boolean doRestore = false;
	private boolean end = false;
	private int loglevel = LOG_WARN;

	private Thread thread = null;
	private boolean refreshScheduled = false;
	private Runnable refresh = new Runnable() {
		public void run () {
			synchronized (instance) {
				ui.refresh();
				refreshScheduled = false;
			}
		}
	};
	private Runnable store = new Runnable() {
		public void run () {
			// perform the actual sync
			try {
				ui.blockForSaving();
				savegame.store(environment);
			} catch (IOException e) {
				log("STOR: save failed: "+e.toString(), LOG_WARN);
				ui.showError("Sync failed.\n" + e.getMessage());
			} finally {
				ui.unblock();
			}
		}
	};

	protected Engine (CartridgeFile cf, OutputStream out) throws IOException {
		gwcfile = cf;
		savegame = cf.getSavegame();
		if (out != null) log = new PrintStream(out);
	}

	protected Engine () {
		/* for test mockups */
	}

	/** creates a new global Engine instance */
	public static Engine newInstance (CartridgeFile cf, OutputStream log, UI ui, LocationService service) throws IOException {
		ui.debugMsg("Creating engine...\n");
		Engine.ui = ui;
		Engine.gps = service;
		instance = new Engine(cf, log);
		return instance;
	}

	/** utility function to dump stack trace and show a semi-meaningful error */
	public static void stacktrace (Throwable e) {
		e.printStackTrace();
		String msg;
		if (vmThread != null) {
			System.out.println(vmThread.currentCoroutine.stackTrace);
			msg = e.toString() + "\n\nstack trace: " + vmThread.currentCoroutine.stackTrace;
		} else {
			msg = e.toString();
		}
		log(msg, LOG_ERROR);
		ui.showError("you hit a bug! please report at openwig.googlecode.com and i'll fix it for you!\n"+msg);
	}

	/** stops Engine */
	public static void kill () {
		if (instance == null) return;
		Timer.kill();
		instance.end = true;
	}

	/** builds and calls a dialog from a Message table */
	public static void message (KahluaTable message) {
		String text = removeHtml((String)message.rawget("Text"));
		log("CALL: MessageBox - " + text.substring(0, Math.min(100, text.length())), LOG_CALL);
		Media media = (Media)message.rawget("Media");
		String button1 = "OK", button2 = null;
		KahluaTable buttons = (KahluaTable)message.rawget("Buttons");
		if (buttons != null) {
			button1 = (String)buttons.rawget(new Double(1));
			button2 = (String)buttons.rawget(new Double(2));
		}
		final LuaClosure callback = (LuaClosure)message.rawget("Callback");

		DialogObject dobj = new DialogObject(null, text, media) {
			public void doCallback (Object value) {
				invokeCallback(callback, value);
			}
		};

		if (button2 != null)
			ui.uiChoice(dobj, new String[] { button1, button2 });
		else
			ui.uiConfirm(dobj, button1);
	}

	/** builds and calls a dialog from a Dialog table */
	public static void dialog (final KahluaTable dialog) {
		if (dialog.len() <= 0) return;

		DialogObject dobj = new DialogObject(null, null, null) {
			private int i = 0;

			public void loadPage (int page) {
				KahluaTable kt = (KahluaTable)dialog.rawget(page);
				if (kt == null) throw new RuntimeException("dialog overrun");
				this.sender = (EventTable)kt.rawget("Sender");
				this.text = removeHtml((String)kt.rawget("Text"));
				this.media = (Media)kt.rawget("Media");
			}

			public void doCallback (Object value) {
				if (value == null) return; /* dialog was canceled */
				if (i >= dialog.len()) return; /* we are at end */
				loadPage(++i);
				log("CALL: Dialog - " + text.substring(0, Math.min(100, text.length())), LOG_CALL);
				ui.uiMessage(this);
			}
		};

		/* XXX this is a hack. Because I want to be able to define DialogObject subclasses inline,
		 * I can't call methods that are not part of the class (in this case, loadPage, to load the
		 * first page of the dialog).
		 * So instead, I call doCallback, which will flip to next page (`i` starts at 0 so next page is 1)
		 * and invoke the uiMessage call by itself.
		 */
		dobj.doCallback("start");
	}

	/** calls input to UI */
	public static void input (final KahluaTable input) {
		String type = (String)input.rawget("InputType");
		String name = (String)input.rawget("Name");
		String text = removeHtml((String)input.rawget("Text"));
		Media media = (Media)input.rawget("Media");
		final LuaClosure callback = (LuaClosure)input.rawget("OnGetInput");

		if (name == null) name = "(unnamed)";
		if (type == null) {
			log("CALL: GetInput without type!", LOG_ERROR);
			return;
		}
		if ("MultipleChoice".equals(type)) {
			KahluaTable choices = (KahluaTable)input.rawget("Choices");
			if (choices == null || choices.isEmpty()) {
				log("CALL: GetInput " + name + " has invalid choices", LOG_ERROR);
				return;
			}
			final int optsize = choices.len();
			final String[] options = new String[optsize];
			for (int i = 0; i < optsize; i++)
				options[i] = (String)choices.rawget(i + 1);
			log("CALL: GetInput " + name + " (multiple choice)", LOG_CALL);

			DialogObject dobj = new DialogObject(null, text, media) {
				public void doCallback (Object value) {
					// value should be Integer
					int i = ((Integer)value).intValue();
					if (i < 0 || i >= optsize) throw new RuntimeException("answer index out of bounds");
					invokeCallbackOn(input, "OnGetInput", options[i]);
				}
			};

			ui.uiChoice(dobj, options);
		} else {
			if (!"Text".equals(type)) {
				log("CALL: GetInput " + name + " has unknown type '" + type + "', assuming Text", LOG_WARN);
			}
			log("CALL: GetInput " + name + " (text)", LOG_CALL);

			DialogObject dobj = new DialogObject(null, text, media) {
				public void doCallback (Object value) {
					invokeCallbackOn(input, "OnGetInput", value);
				}
			};

			ui.uiInput(dobj);
		}
	}

	/** fires the specified event on the specified object in the event thread */
	public static void callEvent (final EventTable subject, final String name, final Object param) {
		if (!subject.hasEvent(name)) return;
		instance.eventRunner.perform(new Runnable() {
			public void run () {
				subject.callEvent(name, param);
				// callEvent handles its failures, so no catch here
			}
		});
	}

	/** invokes a Lua callback in the event thread */
	private static void invokeCallback (final LuaClosure callback, final Object value) {
		if (callback == null) return;
		instance.eventRunner.perform(new Runnable() {
			public void run () {
				try {
					if (value == null)
						Engine.log("CBAK: UI element cancelled", LOG_CALL);
					else
						Engine.log("CBAK: user input is: '" + value.toString() + "'", LOG_CALL);
					Engine.vmThread.call(callback, value, null, null);
					Engine.log("CBAK END", LOG_CALL);
				} catch (Throwable t) {
					stacktrace(t);
					Engine.log("CBAK FAIL", LOG_CALL);
				}
			}
		});
	}

	/** invokes a Lua callback on a ZInput table (basically a simulated callEvent) */
	private static void invokeCallbackOn (final KahluaTable self, final String eventName, final Object value) {
		final LuaClosure callback = (LuaClosure)self.rawget(eventName);
		if (callback == null) {
			Engine.log("CBAK: user input is: '" + value.toString() + "'; no " + eventName + " registered", LOG_CALL);
			return;
		}
		instance.eventRunner.perform(new Runnable() {
			public void run () {
				try {
					if (value == null)
						Engine.log("CBAK: UI element cancelled", LOG_CALL);
					else
						Engine.log("CBAK: user input is: '" + value.toString() + "'", LOG_CALL);
					Engine.vmThread.call(callback, self, value, null);
					Engine.log("CBAK END", LOG_CALL);
				} catch (Throwable t) {
					stacktrace(t);
					Engine.log("CBAK FAIL", LOG_CALL);
				}
			}
		});
	}

	/** extracts media file data from cartridge */
	public static byte[] mediaFile (Media media) throws IOException {
		/*String filename = media.jarFilename();
		return media.getClass().getResourceAsStream("/media/"+filename);*/
		return instance.gwcfile.getFile(media.id);
	}

	/** tries to log the specified message, if verbosity is higher than its level */
	public static void log (String s, int level) {
		if (instance == null || instance.log == null) return;
		if (level < instance.loglevel) return;
		synchronized (instance.log) {
		Calendar now = Calendar.getInstance();
		instance.log.print(now.get(Calendar.HOUR_OF_DAY));
		instance.log.print(':');
		instance.log.print(now.get(Calendar.MINUTE));
		instance.log.print(':');
		instance.log.print(now.get(Calendar.SECOND));
		instance.log.print('|');
		instance.log.print((int)(gps.getLatitude() * 10000 + 0.5) / 10000.0);
		instance.log.print('|');
		instance.log.print((int)(gps.getLongitude() * 10000 + 0.5) / 10000.0);
		instance.log.print('|');
		instance.log.print(gps.getAltitude());
		instance.log.print('|');
		instance.log.print(gps.getPrecision());
		instance.log.print("|:: ");
		instance.log.println(s);
		instance.log.flush();
		}
	}

	private static void replace (String source, String pattern, String replace, StringBuffer builder) {
		int pos = 0;
		int pl = pattern.length();
		builder.delete(0, builder.length());
		while (pos < source.length()) {
			int np = source.indexOf(pattern, pos);
			if (np == -1) break;
			builder.append(source.substring(pos, np));
			builder.append(replace);
			pos = np + pl;
		}
		builder.append(source.substring(pos));
	}
	
	/** strips a subset of HTML that tends to appear in descriptions generated
	 * by Groundspeak Builder
	 */
	public static String removeHtml (String s) {
		if (s == null) return "";
		StringBuffer sb = new StringBuffer(s.length());
		replace(s, "<BR>", "\n", sb);
		replace(sb.toString(), "&nbsp;", " ", sb);
		replace(sb.toString(), "&lt;", "<", sb);
		replace(sb.toString(), "&gt;", ">", sb);
		replace(sb.toString(), "&amp;", "&", sb);
		return sb.toString();
	}

	public static void refreshUI () {
		synchronized (instance) {
			if (!instance.refreshScheduled) {
				instance.refreshScheduled = true;
				instance.eventRunner.perform(instance.refresh);
			}
		}
	}

	/** requests save in event thread */
	public static void requestSync () {
		instance.eventRunner.perform(instance.store);
	}

	/** starts Engine's thread */
	public void start () {
		thread = new Thread(this);
		thread.start();
	}

	/** marks game for resuming and starts thread */
	public void restore () {
		doRestore = true;
		start();
	}
	
	/** prepares Lua state and some bookkeeping */
	protected void prepareState ()
	throws IOException {
		ui.debugMsg("Creating state...\n");
		environment = platform.newEnvironment();
		vmThread = new KahluaThread(platform, environment);

		/*write("Registering base libs...\n");
		BaseLib.register(state);
		MathLib.register(state);
		StringLib.register(state);
		CoroutineLib.register(state);
		OsLib.register(state);*/

		ui.debugMsg("Registering WIG libs...\n");
		WherigoLib.register(vmThread, environment);

		ui.debugMsg("Building javafunc map...\n");
		savegame.buildFuncMap(environment);

		ui.debugMsg("Building event queue...\n");
		eventRunner = new BackgroundRunner(true);
		eventRunner.setQueueListener(new Runnable() {
			public void run () {
				ui.refresh();
			}
		});

		ui.debugMsg("Loading gwc...");
		if (gwcfile == null) throw new IOException("invalid cartridge file");

		ui.debugMsg("pre-setting properties...");
		player.rawset("CompletionCode", gwcfile.code);
		player.rawset("Name", gwcfile.member);

		ui.debugMsg("loading code...");
		byte[] lbc = gwcfile.getBytecode();

		ui.debugMsg("parsing...");
		gwcclosure = Prototype.loadByteCode(new ByteArrayInputStream(lbc), environment);
		savegame.walkPrototype(gwcclosure.prototype);
	}

	/** invokes game restore */
	private void restoreGame ()
	throws IOException {
		ui.debugMsg("Restoring saved state...");
		cartridge = new Cartridge();
		savegame.restore(environment);
	}

	/** invokes creation of clean new game environment */
	private void newGame ()
	throws IOException {
		// starting game normally

		ui.debugMsg("calling top-level...\n");
		vmThread.call(gwcclosure, null, null, null);
	}

	/** main loop - periodically copy location data into Lua and evaluate zone positions */
	private void mainloop () {
		try {
			while (!end) {
				try {
					if (gps.getLatitude() != player.position.latitude
					|| gps.getLongitude() != player.position.longitude
					|| gps.getAltitude() != player.position.altitude) {
						player.refreshLocation();
					}
					cartridge.tick();
				} catch (Exception e) {
					stacktrace(e);
				}

				try { Thread.sleep(1000); } catch (InterruptedException e) { }
			}
			if (log != null) log.close();
		} catch (Throwable t) {
			ui.end();
			stacktrace(t);
		} finally {
			instance = null;
			vmThread = null;
			if (eventRunner != null) eventRunner.kill();
			eventRunner = null;
		}
	}

	/** thread's run() method that does all the work in the right order */
	public void run () {
		try {
			if (log != null) log.println("-------------------\ncartridge " + gwcfile.name + " started (openWIG r" + VERSION + ")\n-------------------");
			prepareState ();

			if (doRestore) restoreGame();
			else newGame();

			// drop gwcclosure, we won't need it anymore
			gwcclosure = null;

			loglevel = LOG_PROP;

			ui.debugMsg("Starting game...\n");
			ui.start();

			player.refreshLocation();
			cartridge.callEvent(doRestore ? "OnRestore" : "OnStart", null);
			ui.refresh();
			eventRunner.unpause();

			mainloop();
		} catch (IOException e) {
			ui.showError("Could not load cartridge: "+e.getMessage());
		} catch (Throwable t) {
			stacktrace(t);
		} finally {
			ui.end();
		}
	}

	/** stores current game state */
	public void store () {
		store.run();
	}
}