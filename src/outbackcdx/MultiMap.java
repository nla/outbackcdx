package outbackcdx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mostly acts like a {@code Map<K,V>}, but secretly supports a list of values
 * per key, using {@link #add(Object, Object)} and {@link #getAll(Object)}. Not
 * all {@code Map} methods are implemented.
 */
public class MultiMap<K, V> implements Map<K, V> {
    
    protected Map<K,List<V>> inner;

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
        throw new RuntimeException("not implemented");
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
