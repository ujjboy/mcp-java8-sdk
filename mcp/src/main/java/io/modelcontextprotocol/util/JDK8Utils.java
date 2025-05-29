package io.modelcontextprotocol.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * @author <a href=mailto:zhanggeng.zg@antfin.com>GengZhang</a>
 */
public class JDK8Utils {

    public static <T> List<T> streamToList(Stream<T> stream) {
        return (List<T>) Collections.unmodifiableList(new ArrayList<>(Arrays.asList(stream.toArray())));
    }

    public static <T> List<T> listOf(T... a) {
        return Arrays.asList(a);
    }

    public static <K, V> Map<K, V> mapOf() {
        return (Map<K,V>) Collections.EMPTY_MAP;
    }

    public static <K, V> Map<K, V> mapOf(K k1, V v1) {
        HashMap<K, V> map = new HashMap<K, V>();
        map.put(k1, v1);
        return Collections.unmodifiableMap(map);
    }
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2){
        HashMap<K, V> map = new HashMap<K, V>();
        map.put(k1, v1);
        map.put(k2, v2);
        return Collections.unmodifiableMap(map);
    }
    public static <K, V> Map<K, V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) {
        HashMap<K, V> map = new HashMap<K, V>();
        map.put(k1, v1);
        map.put(k2, v2);
        map.put(k3, v3);
        return Collections.unmodifiableMap(map);
    }
}
