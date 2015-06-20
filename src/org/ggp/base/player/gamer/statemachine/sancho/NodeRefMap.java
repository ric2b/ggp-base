package org.ggp.base.player.gamer.statemachine.sancho;

import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Hash-table for storing longs without the object allocation overhead for Long.
 *
 * @param <K>
 */
public class NodeRefMap<K>
{
  /**
   * The maximum capacity, used if a higher value is implicitly specified
   * by either of the constructors with arguments.
   * MUST be a power of two <= 1<<30.
   */
  private static final int MAXIMUM_CAPACITY = 1 << 30;

  /**
   * An empty table instance to share when the table is not inflated.
   */
  private static final Entry<?>[] EMPTY_TABLE = {};

  /**
   * The table, resized as necessary. Length MUST Always be a power of two.
   */
  @SuppressWarnings("unchecked")
  Entry<K>[] table = (Entry<K>[]) EMPTY_TABLE;

  /**
   * The number of key-value mappings contained in this map.
   */
  int size;

  /**
   * The next size value at which to resize (capacity * load factor).
   * @serial
   */
  // If table == EMPTY_TABLE then this is the initial capacity at which the
  // table will be created when inflated.
  private int threshold;

  /**
   * The load factor for the hash table.
   *
   * @serial
   */
  private final float loadFactor;

  /**
   * The default threshold of map capacity above which alternative hashing is
   * used for String keys. Alternative hashing reduces the incidence of
   * collisions due to weak hash code calculation for String keys.
   * <p/>
   * This value may be overridden by defining the system property
   * {@code jdk.map.althashing.threshold}. A property value of {@code 1}
   * forces alternative hashing to be used at all times whereas
   * {@code -1} value ensures that alternative hashing is never used.
   */
  private static final int ALTERNATIVE_HASHING_THRESHOLD_DEFAULT = Integer.MAX_VALUE;

  /**
   * holds values which can't be initialized until after VM is booted.
   */
  private static class Holder {

    /**
     * Table capacity above which to switch to use alternative hashing.
     */
    static final int ALTERNATIVE_HASHING_THRESHOLD;

    static {
      String altThreshold = java.security.AccessController.doPrivileged(
                                                                        new sun.security.action.GetPropertyAction(
                                                                            "jdk.map.althashing.threshold"));

      int threshold;
      try {
        threshold = (null != altThreshold)
            ? Integer.parseInt(altThreshold)
            : ALTERNATIVE_HASHING_THRESHOLD_DEFAULT;

            // disable alternative hashing if -1
            if (threshold == -1) {
              threshold = Integer.MAX_VALUE;
            }

            if (threshold < 0) {
              throw new IllegalArgumentException("value must be positive integer.");
            }
      } catch(IllegalArgumentException failed) {
        throw new Error("Illegal value for 'jdk.map.althashing.threshold'", failed);
      }

      ALTERNATIVE_HASHING_THRESHOLD = threshold;
    }
  }

  /**
   * A randomizing value associated with this instance that is applied to
   * hash code of keys to make hash collisions harder to find. If 0 then
   * alternative hashing is disabled.
   */
  private int hashSeed = 0;

  /**
   * Constructs an empty <tt>HashMap</tt> with the specified initial
   * capacity and load factor.
   *
   * @param  initialCapacity the initial capacity
   * @param  xiLoadFactor      the load factor
   * @throws IllegalArgumentException if the initial capacity is negative
   *         or the load factor is nonpositive
   */
  public NodeRefMap(int initialCapacity, float xiLoadFactor)
  {
    if (initialCapacity < 0)
      throw new IllegalArgumentException("Illegal initial capacity: " +
          initialCapacity);
    if (initialCapacity > MAXIMUM_CAPACITY)
      initialCapacity = MAXIMUM_CAPACITY;
    if (xiLoadFactor <= 0 || Float.isNaN(xiLoadFactor))
      throw new IllegalArgumentException("Illegal load factor: " +
          xiLoadFactor);

    this.loadFactor = xiLoadFactor;
    threshold = initialCapacity;
  }

  private static int roundUpToPowerOf2(int number) {
    // assert number >= 0 : "number must be non-negative";
    int rounded = number >= MAXIMUM_CAPACITY
        ? MAXIMUM_CAPACITY
        : (rounded = Integer.highestOneBit(number)) != 0
        ? (Integer.bitCount(number) > 1) ? rounded << 1 : rounded
                                         : 1;

    return rounded;
  }

  /**
   * Inflates the table.
   */
  @SuppressWarnings("unchecked")
  private void inflateTable(int toSize) {
    // Find a power of 2 >= toSize
    int capacity = roundUpToPowerOf2(toSize);

    threshold = (int) Math.min(capacity * loadFactor, MAXIMUM_CAPACITY + 1);
    table = new Entry[capacity];
    initHashSeedAsNeeded(capacity);
  }

  // internal utilities

  /**
   * Initialize the hashing mask value. We defer initialization until we
   * really need it.
   */
  private boolean initHashSeedAsNeeded(int capacity)
  {
    boolean currentAltHashing = hashSeed != 0;
    boolean useAltHashing = sun.misc.VM.isBooted() &&
        (capacity >= Holder.ALTERNATIVE_HASHING_THRESHOLD);
    boolean switching = currentAltHashing ^ useAltHashing;
    if (switching) {
      hashSeed = useAltHashing
          ? sun.misc.Hashing.randomHashSeed(this)
          : 0;
    }
    return switching;
  }

  /**
   * Retrieve object hash code and applies a supplemental hash function to the
   * result hash, which defends against poor quality hash functions.  This is
   * critical because HashMap uses power-of-two length hash tables, that
   * otherwise encounter collisions for hashCodes that do not differ
   * in lower bits. Note: Null keys always map to hash 0, thus index 0.
   */
  private int hash(K k)
  {
    int h = hashSeed;
    if (0 != h && k instanceof String) {
      return sun.misc.Hashing.stringHash32((String) k);
    }

    h ^= k.hashCode();

    // This function ensures that hashCodes that differ only by
    // constant multiples at each bit position have a bounded
    // number of collisions (approximately 8 at default load factor).
    h ^= (h >>> 20) ^ (h >>> 12);
    return h ^ (h >>> 7) ^ (h >>> 4);
  }

  /**
   * Returns index for hash code h.
   */
  private static int indexFor(int h, int length)
  {
    return h & (length-1);
  }

  /**
   * @return the value to which the specified key is mapped,
   * or TreeNode.NULL_REF if this map contains no mapping for the key.
   *
   * @param key - the key.
   */
  public long get(K key)
  {
    Entry<K> entry = getEntry(key);
    return null == entry ? TreeNode.NULL_REF : entry.getValue();
  }

  /**
   * Returns <tt>true</tt> if this map contains a mapping for the
   * specified key.
   *
   * @param   key   The key whose presence in this map is to be tested
   * @return <tt>true</tt> if this map contains a mapping for the specified
   * key.
   */
  public boolean containsKey(K key)
  {
    return getEntry(key) != null;
  }

  /**
   * Returns the entry associated with the specified key in the
   * HashMap.  Returns null if the HashMap contains no mapping
   * for the key.
   */
  private Entry<K> getEntry(K key)
  {
    if (size == 0)
    {
      return null;
    }

    int hash = (key == null) ? 0 : hash(key);
    for (Entry<K> e = table[indexFor(hash, table.length)];
        e != null;
        e = e.next) {
      Object k;
      if (e.hash == hash &&
          ((k = e.key) == key || (key != null && key.equals(k))))
        return e;
    }
    return null;
  }

  /**
   * Associates the specified value with the specified key in this map.
   * If the map previously contained a mapping for the key, the old
   * value is replaced.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @return the previous value associated with <tt>key</tt>, or
   *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
   *         (A <tt>null</tt> return can also indicate that the map
   *         previously associated <tt>null</tt> with <tt>key</tt>.)
   */
  public long put(K key, long value) {
    if (table == EMPTY_TABLE) {
      inflateTable(threshold);
    }
    int hash = hash(key);
    int i = indexFor(hash, table.length);
    for (Entry<K> e = table[i]; e != null; e = e.next) {
      Object k;
      if (e.hash == hash && ((k = e.key) == key || key.equals(k))) {
        long oldValue = e.value;
        e.value = value;
        return oldValue;
      }
    }

    addEntry(hash, key, value, i);
    return TreeNode.NULL_REF;
  }

  /**
   * Rehashes the contents of this map into a new array with a
   * larger capacity.  This method is called automatically when the
   * number of keys in this map reaches its threshold.
   *
   * If current capacity is MAXIMUM_CAPACITY, this method does not
   * resize the map, but sets threshold to Integer.MAX_VALUE.
   * This has the effect of preventing future calls.
   *
   * @param newCapacity the new capacity, MUST be a power of two;
   *        must be greater than current capacity unless current
   *        capacity is MAXIMUM_CAPACITY (in which case value
   *        is irrelevant).
   */
  private void resize(int newCapacity)
  {
    Entry<K>[] oldTable = table;
    int oldCapacity = oldTable.length;
    if (oldCapacity == MAXIMUM_CAPACITY) {
      threshold = Integer.MAX_VALUE;
      return;
    }

    @SuppressWarnings("unchecked")
    Entry<K>[] newTable = new Entry[newCapacity];
    transfer(newTable, initHashSeedAsNeeded(newCapacity));
    table = newTable;
    threshold = (int)Math.min(newCapacity * loadFactor, MAXIMUM_CAPACITY + 1);
  }

  /**
   * Transfers all entries from current table to newTable.
   */
  private void transfer(Entry<K>[] newTable, boolean rehash)
  {
    int newCapacity = newTable.length;
    for (Entry<K> e : table) {
      while(null != e) {
        Entry<K> next = e.next;
        if (rehash) {
          e.hash = null == e.key ? 0 : hash(e.key);
        }
        int i = indexFor(e.hash, newCapacity);
        e.next = newTable[i];
        newTable[i] = e;
        e = next;
      }
    }
  }

  /**
   * Removes the mapping for the specified key from this map if present.
   *
   * @param  key key whose mapping is to be removed from the map
   * @return the previous value associated with <tt>key</tt>, or
   *         <tt>null</tt> if there was no mapping for <tt>key</tt>.
   *         (A <tt>null</tt> return can also indicate that the map
   *         previously associated <tt>null</tt> with <tt>key</tt>.)
   */
  public long remove(K key)
  {
    Entry<K> e = removeEntryForKey(key);
    return (e == null ? TreeNode.NULL_REF : e.value);
  }

  /**
   * Removes and returns the entry associated with the specified key
   * in the HashMap.  Returns null if the HashMap contains no mapping
   * for this key.
   */
  Entry<K> removeEntryForKey(K key)
  {
    if (size == 0)
    {
      return null;
    }

    int hash = (key == null) ? 0 : hash(key);
    int i = indexFor(hash, table.length);
    Entry<K> prev = table[i];
    Entry<K> e = prev;

    while (e != null) {
      Entry<K> next = e.next;
      Object k;
      if (e.hash == hash &&
          ((k = e.key) == key || (key != null && key.equals(k)))) {
        size--;
        if (prev == e)
          table[i] = next;
        else
          prev.next = next;
        return e;
      }
      prev = e;
      e = next;
    }

    return e;
  }

  /**
   * Removes all of the mappings from this map.
   * The map will be empty after this call returns.
   */
  public void clear()
  {
    Arrays.fill(table, null);
    size = 0;
  }

  private static class Entry<K>
  {
    final K key;
    long value;
    Entry<K> next;
    int hash;

    /**
     * Creates new entry.
     *
     * @param h - the hash (of the key).
     * @param k - the key.
     * @param v - the value.
     * @param n - the next entry.
     */
    public Entry(int h, K k, long v, Entry<K> n)
    {
      value = v;
      next = n;
      key = k;
      hash = h;
    }

    public final K getKey() {
      return key;
    }

    public final long getValue() {
      return value;
    }

    @Override
    public final boolean equals(Object o) {
      if (!(o instanceof Entry))
        return false;
      @SuppressWarnings("unchecked")
      Entry<K> e = (Entry<K>)o;
      Object k1 = getKey();
      Object k2 = e.getKey();
      if (k1 == k2 || (k1 != null && k1.equals(k2))) {
        long v1 = getValue();
        long v2 = e.getValue();
        if (v1 == v2)
          return true;
      }
      return false;
    }

    @Override
    public final int hashCode() {
      return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue()); // !! ARR Need to use Long's hashCode.
    }

    @Override
    public final String toString() {
      return getKey() + "=" + getValue();
    }
  }

  /**
   * Adds a new entry with the specified key, value and hash code to
   * the specified bucket.  It is the responsibility of this
   * method to resize the table if appropriate.
   *
   * Subclass overrides this to alter the behavior of put method.
   */
  private void addEntry(int hash, K key, long value, int bucketIndex)
  {
    if ((size >= threshold) && (null != table[bucketIndex])) {
      resize(2 * table.length);
      hash = (null != key) ? hash(key) : 0;
      bucketIndex = indexFor(hash, table.length);
    }

    createEntry(hash, key, value, bucketIndex);
  }

  /**
   * Like addEntry except that this version is used when creating entries
   * as part of Map construction or "pseudo-construction" (cloning,
   * deserialization).  This version needn't worry about resizing the table.
   *
   * Subclass overrides this to alter the behavior of HashMap(Map),
   * clone, and readObject.
   */
  private void createEntry(int hash, K key, long value, int bucketIndex)
  {
    Entry<K> e = table[bucketIndex];
    table[bucketIndex] = new Entry<>(hash, key, value, e);
    size++;
  }

  private abstract class HashIterator<E> implements Iterator<E>
  {
    private Entry<K> next;        // next entry to return
    private int index;            // current slot
    private Entry<K> current;     // current entry

    public HashIterator()
    {
      if (size > 0) { // advance to first entry
        Entry<K>[] t = table;
        while (index < t.length && (next = t[index++]) == null)
        {
          /* Do nothing */
        }
      }
    }

    @Override
    public final boolean hasNext() {
      return next != null;
    }

    public final Entry<K> nextEntry() {
      Entry<K> e = next;
      if (e == null)
        throw new NoSuchElementException();

      if ((next = e.next) == null) {
        Entry<K>[] t = table;
        while (index < t.length && (next = t[index++]) == null)
        {
          /* Do nothing */
        }
      }
      current = e;
      return e;
    }

    @Override
    public void remove()
    {
      if (current == null)
        throw new IllegalStateException();

      K k = current.key;
      current = null;
      NodeRefMap.this.removeEntryForKey(k);
    }
  }

  /**
   * Iterator by key.
   */
  class KeyIterator extends HashIterator<K>
  {
    @Override
    public K next()
    {
      return nextEntry().getKey();
    }
  }

  // Views

  private Set<K> keySet = null;

  /**
   * @return a {@link Set} view of the keys contained in this map.  The set is backed by the map.
   */
  public Set<K> keySet()
  {
    Set<K> ks = keySet;
    return (ks != null ? ks : (keySet = new KeySet()));
  }

  /**
   * The keys in this class, as a set.
   */
  class KeySet extends AbstractSet<K>
  {
    @Override
    public Iterator<K> iterator()
    {
      return new KeyIterator();
    }

    @Override
    public int size()
    {
      return size;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o)
    {
      return containsKey((K)o);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean remove(Object o)
    {
      return NodeRefMap.this.removeEntryForKey((K)o) != null;
    }

    @Override
    public void clear()
    {
      NodeRefMap.this.clear();
    }
  }
}
