package net.sf.sdedit.util;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

/**
 * A <tt>LookupTable</tt> stores a set of <tt>T</tt> objects with key attributes
 * of which some can be null. These objects provide some configured values to be
 * used for other (fact) objects which have (in part) the same key attributes.
 * <p>
 * The <tt>T</tt> object matching a fact can be found by
 * {@linkplain #getBestMatch(Object)}. A <tt>T</tt> object matches a fact if the
 * fact has the same values in all key attributes of the <tt>T</tt> object. If a
 * key attribute of the <tt>T</tt> object is null, the fact object's value of
 * that attribute does not matter. If there is more than one such match, the
 * <tt>T</tt> object with the least null keys will be returned. If there is more
 * than one matching <tt>T</tt> with the same minimal number of null keys, an
 * exception will be thrown.
 * 
 * @param <T>
 *            The class of the objects with key attributes
 */
public class LookupTable<T> {

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface Column {

		boolean isKey();

	}

	private final Set<T> rows;

	private final Map<String, PropertyDescriptor> keys;

	private final Map<String, PropertyDescriptor> values;

	private final Class<T> type;

	public LookupTable(Class<T> type) {
		this.type = type;
		this.rows = new LinkedHashSet<T>();
		this.keys = new HashMap<String, PropertyDescriptor>();
		this.values = new HashMap<String, PropertyDescriptor>();
		try {
			initialize();
		} catch (IntrospectionException ie) {
			throw new IllegalArgumentException(ie);
		}
	}

	public int getSize() {
		return rows.size();
	}

	public String toString() {
		PWriter pw = PWriter.create();
		for (T t : rows) {
			pw.println(Utilities.toMap(t).toString());
		}
		return pw.toString();
	}

	private void initialize() throws IntrospectionException {
		BeanInfo beanInfo = Introspector.getBeanInfo(type);
		PropertyDescriptor[] propertyDescriptors = beanInfo.getPropertyDescriptors();
		for (int i = 0; i < propertyDescriptors.length; i++) {
			PropertyDescriptor property = propertyDescriptors[i];
			Method method = property.getReadMethod();
			if (method != null && method.isAnnotationPresent(Column.class)) {
				Column column = method.getAnnotation(Column.class);
				if (column.isKey()) {
					keys.put(property.getName(), property);
				} else {
					values.put(property.getName(), property);
				}
			}
		}
	}

	private Map<String, Object> getKeys(T t) {
		Map<String, Object> k = new TreeMap<String, Object>();
		for (Entry<String, PropertyDescriptor> entry : keys.entrySet()) {
			Object value = null;
			if (t != null) {
				try {
					value = entry.getValue().getReadMethod().invoke(t);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}
			k.put(entry.getKey(), value);
		}
		return k;
	}

	private int countNullKeys(T t) {
		int nulls = 0;
		for (PropertyDescriptor p : keys.values()) {
			Object value;
			try {
				value = p.getReadMethod().invoke(t);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
			if (value == null) {
				nulls++;
			}
		}
		return nulls;
	}

	public void add(T t) {
		Map<Integer, List<T>> matches = getMatches(getKeys(t));
		int nulls = countNullKeys(t);
		List<T> existing = matches.get(nulls);
		if (existing != null) {
			rows.remove(existing.get(0));
		}
		rows.add(t);
	}

	public T getBestMatch(Object o) {
		Map<String, Object> okeys = getKeys(null);
		for (Entry<String, Object> entry : Utilities.toMap(o).entrySet()) {
			String key = entry.getKey();
			if (okeys.containsKey(key)) {
				okeys.put(entry.getKey(), cast(keys.get(key).getPropertyType(), entry.getValue()));
			}
		}
		TreeMap<Integer, List<T>> matches = getMatches(okeys);
		if (matches.isEmpty()) {
			return null;
		}
		List<T> best = matches.firstEntry().getValue();
		if (best.size() == 1) {
			return best.get(0);
		}
		PWriter p = PWriter.create();
		p.println("Could not find unique best match for " + Utilities.toMap(o));
		p.println("There are " + best.size() + " matches:");
		for (T b : best) {
			p.println(Utilities.toMap(b));
		}
		throw new IllegalArgumentException(p.toString());
	}

	private TreeMap<Integer, List<T>> getMatches(Map<String, Object> tkeys) {
		if (!tkeys.keySet().equals(keys.keySet())) {
			throw new IllegalArgumentException("map keys do not match: " + tkeys.keySet());
		}
		TreeMap<Integer, List<T>> matches = new TreeMap<Integer, List<T>>();
		rows: for (T row : rows) {
			Map<String, Object> rkeys = getKeys(row);
			int nulls = 0;
			for (Pair<Object, Object> pair : Utilities.pairs(tkeys.values(), rkeys.values())) {
				if (pair.getSecond() == null) {
					nulls++;
				} else if (pair.getFirst() == null) {
					// a new object with a null column does not match an
					// existing object that has a defined value
					continue rows;
				} else if (!pair.getFirst().equals(pair.getSecond())) {
					continue rows;
				}
			}
			List<T> list = matches.get(nulls);
			if (list == null) {
				list = new ArrayList<T>();
				matches.put(nulls, list);
			}
			list.add(row);
		}
		return matches;
	}

	private Object cast(Class<?> type, Object value) {
		if (value == null || type.isInstance(value)) {
			return value;
		}
		return ObjectFactory.createFromString(type, value.toString());
	}

	public T add(String... keyVal) {
		Map<String, Object> map = new HashMap<String, Object>();
		for (int i = 0; i < keyVal.length; i += 2) {
			map.put(keyVal[i], keyVal[i + 1]);
		}
		return add(map);
	}

	public T add(Map<String, Object> map) {
		T row;
		try {
			row = type.newInstance();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
		for (PropertyDescriptor desc : keys.values()) {
			Object value = map.remove(desc.getName());
			value = cast(desc.getPropertyType(), value);
			try {
				desc.getWriteMethod().invoke(row, value);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		for (PropertyDescriptor desc : values.values()) {
			Object value = map.remove(desc.getName());
			value = cast(desc.getPropertyType(), value);
			try {
				desc.getWriteMethod().invoke(row, value);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		if (!map.isEmpty()) {
			throw new IllegalArgumentException("some keys could not be found: " + map.keySet());
		}
		add(row);
		return row;
	}

}