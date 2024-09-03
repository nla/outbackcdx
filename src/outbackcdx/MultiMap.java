package outbackcdx;

import java.util.*;

/**
 * Mostly acts like a {@code Map<K,V>}, but secretly supports a list of values
 * per key, using {@link #add(Object, Object)} and {@link #getAll(Object)}. Not
 * all {@code Map} methods are implemented.
 */
public class MultiMap<K, V> implements Map<K, V> {
    
    protected Map<K,List<V>> inner;

    @SuppressWarnings("unchecked")
    public static <K,V> MultiMap<K,V> of(Object... entries) {
        var map = new MultiMap<K,V>();
        for (int i = 0; i < entries.length; i += 2) {
            map.add((K)entries[i], (V)entries[i + 1]);
        }
        return map;
    }

    public MultiMap() {
        inner = new HashMap<>();
    }

    @Override
    public int size() {
        return inner.size();
    }

    @Override
    public boolean isEmpty() {
        return inner.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return inner.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public V get(Object key) {
        List<V> values = inner.get(key);

        if (values != null) {
            if (values.size() == 1) {
                return values.get(0);
            } else {
                throw new RuntimeException("there are multiple values for key " + key);
            }
        } else {
            return null;
        }
    }

    @Override
    public V put(K key, V value) {
        inner.put(key, new ArrayList<V>(Arrays.asList(value)));
        return value;
    }

    @Override
    public V remove(Object key) {
        List<V> values = inner.remove(key);
        if (values != null) {
            return values.get(0);
        } else {
            return null;
        }
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void clear() {
        inner.clear();
    }

    @Override
    public Set<K> keySet() {
        return inner.keySet();
    }

    @Override
    public Collection<V> values() {
        throw new RuntimeException("not implemented");
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {

            @Override
            public Iterator<Entry<K, V>> iterator() {
                return new Iterator<>() {
                    final Iterator<Entry<K, List<V>>> entryIterator = inner.entrySet().iterator();
                    K key;
                    Iterator<V> values;

                    @Override
                    public boolean hasNext() {
                        while (values == null || !values.hasNext()) {
                            if (!entryIterator.hasNext()) return false;
                            Entry<K, List<V>> next = entryIterator.next();
                            key = next.getKey();
                            values = next.getValue().iterator();
                        }
                        return true;
                    }

                    @Override
                    public Entry<K, V> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        return new AbstractMap.SimpleEntry<>(key, values.next());
                    }
                };
            }

            @Override
            public int size() {
                return inner.values().stream().mapToInt(List::size).sum();
            }
        };
    }

    public List<V> getAll(K k) {
        return inner.get(k);
    }

    public void add(K key, V value) {
        List<V> values = this.getAll(key);
        if (values != null) {
            values.add(value);
        } else {
            this.put(key, value);
        }
    }
    
    @Override
    public String toString() {
        return inner.toString();
    }
}
