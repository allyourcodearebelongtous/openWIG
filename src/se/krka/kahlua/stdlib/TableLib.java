/*
Copyright (c) 2007-2009 Kristofer Karlsson <kristofer.karlsson@gmail.com>
and Jan Matejek <ja@matejcik.cz>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */
package se.krka.kahlua.stdlib;

import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaState;
import se.krka.kahlua.vm.LuaTable;

public final class TableLib implements JavaFunction {

	private static final int CONCAT = 0;
	private static final int INSERT = 1;
	private static final int REMOVE = 2;
	private static final int MAXN = 3;
	private static final int SORT = 4;
	private static final int NUM_FUNCTIONS = 5;
	
	private static String[] names;
	private static TableLib[] functions;

	static {
		names = new String[NUM_FUNCTIONS];
		names[CONCAT] = "concat";
		names[INSERT] = "insert";
		names[REMOVE] = "remove";
		names[MAXN] = "maxn";
		names[SORT] = "sort";
	}
	
	private int index;

	public TableLib (int index) {
		this.index = index;
	}

	public static void register (LuaState state) {
		initFunctions();
		LuaTable table = new LuaTable();
		state.getEnvironment().rawset("table", table);

		for (int i = 0; i < NUM_FUNCTIONS; i++) {
			table.rawset(names[i], functions[i]);
		}
	}

	private static synchronized void initFunctions () {
		if (functions == null) {
			functions = new TableLib[NUM_FUNCTIONS];
			for (int i = 0; i < NUM_FUNCTIONS; i++) {
				functions[i] = new TableLib(i);
			}
		}
	}

	public String toString () {
		return "table." + names[index];
	}

	public int call (LuaCallFrame callFrame, int nArguments) {
		switch (index) {
			case CONCAT:
				return concat(callFrame, nArguments);
			case INSERT:
				return insert(callFrame, nArguments);
			case REMOVE:
				return remove(callFrame, nArguments);
			case MAXN:
				return maxn(callFrame, nArguments);
			default:
				return 0;
		}
	}

	private static int concat (LuaCallFrame callFrame, int nArguments) {
		BaseLib.luaAssert(nArguments >= 1, "expected table, got no arguments");
		LuaTable table = (LuaTable)callFrame.get(0);

		String separator = "";
		if (nArguments >= 2) {
			separator = BaseLib.rawTostring(callFrame.get(1));
		}

		int first = 1;
		if (nArguments >= 3) {
			Double firstDouble = BaseLib.rawTonumber(callFrame.get(2));
			first = firstDouble.intValue();
		}

		int last;
		if (nArguments >= 4) {
			Double lastDouble = BaseLib.rawTonumber(callFrame.get(3));
			last = lastDouble.intValue();
		} else {
			last = table.len();
		}

		StringBuffer buffer = new StringBuffer();
		for (int i = first; i <= last; i++) {
			if (i > first) {
				buffer.append(separator);
			}

			Double key = LuaState.toDouble(i);
			Object value = table.rawget(key);
			buffer.append(BaseLib.rawTostring(value));
		}

		return callFrame.push(buffer.toString());
	}
	
	public static void insert (LuaState state, LuaTable table, Object element) {
		insert(state, table, table.len() + 1, element);
	}

	public static void insert (LuaState state, LuaTable table, int position, Object element) {
		int len = table.len();
		for (int i = len; i >= position; i--) {
			state.tableSet(table, LuaState.toDouble(i+1), state.tableGet(table, LuaState.toDouble(i)));
		}
		state.tableSet(table, LuaState.toDouble(position), element);
	}

	private static int insert (LuaCallFrame callFrame, int nArguments) {
		BaseLib.luaAssert(nArguments >= 2, "Not enough arguments");
		LuaTable t = (LuaTable)callFrame.get(0);
		int pos = t.len() + 1;
		Object elem = null;
		if (nArguments > 2) {
			pos = BaseLib.rawTonumber(callFrame.get(1)).intValue();
			elem = callFrame.get(2);
		} else {
			elem = callFrame.get(1);
		}
		insert(callFrame.thread.state, t, pos, elem);
		return 0;
	}
	
	public static Object remove (LuaState state, LuaTable table) {
		return remove(state, table, table.len());
	}
	
	public static Object remove (LuaState state, LuaTable table, int position) {
		Object ret = state.tableGet(table, LuaState.toDouble(position));
		int len = table.len();
		for (int i = position; i < len; i++) {
			state.tableSet(table, LuaState.toDouble(i), state.tableGet(table, LuaState.toDouble(i+1)));
		}
		state.tableSet(table, LuaState.toDouble(len), null);
		return ret;
	}

	public static void removeItem (LuaTable table, Object item) {
		if (item == null) return;
		Object key = null;
		while ((key = table.next(key)) != null) {
			if (item.equals(table.rawget(key))) {
				table.rawset(key, null);
				return;
			}
		}
	}
	
	private static int remove (LuaCallFrame callFrame, int nArguments) {
		BaseLib.luaAssert(nArguments >= 1, "expected table, got no arguments");
		LuaTable t = (LuaTable)callFrame.get(0);
		int pos = t.len();
		if (nArguments > 1) {
			pos = BaseLib.rawTonumber(callFrame.get(1)).intValue();
		}
		callFrame.push(remove(callFrame.thread.state, t, pos));
		return 1;
	}
	
	private static int maxn (LuaCallFrame callFrame, int nArguments) {
		BaseLib.luaAssert(nArguments >= 1, "expected table, got no arguments");
		LuaTable t = (LuaTable)callFrame.get(0);
		Object key = null;
		int max = 0;
		while ((key = t.next(key)) != null) {
			if (key instanceof Double) {
				int what = (int)LuaState.fromDouble(key);
				if (what > max) max = what;
			}
		}
		callFrame.push(LuaState.toDouble(max));
		return 1;
	}

	public static Object find (LuaTable table, Object item) {
		if (item == null) return null;
		Object key = null;
		while ((key = table.next(key)) != null) {
			if (item.equals(table.rawget(key))) {
				return key;
			}
		}
		return null;
	}
	
	public static boolean contains (LuaTable table, Object item) {
		return find(table, item) != null;
	}
}
