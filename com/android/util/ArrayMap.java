/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.util;

import libcore.util.EmptyArray;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * ArrayMap is a generic key->value mapping data structure that is
 * designed to be more memory efficient than a traditional {@link java.util.HashMap}.
 * It keeps its mappings in an array data structure -- an integer array of hash
 * codes for each item, and an Object array of the key/value pairs.  This allows it to
 * avoid having to create an extra object for every entry put in to the map, and it
 * also tries to control the growth of the size of these arrays more aggressively
 * (since growing them only requires copying the entries in the array, not rebuilding
 * a hash map).
 *
 * <p>Note that this implementation is not intended to be appropriate for data structures
 * that may contain large numbers of items.  It is generally slower than a traditional
 * HashMap, since lookups require a binary search and adds and removes require inserting
 * and deleting entries in the array.  For containers holding up to hundreds of items,
 * the performance difference is not significant, less than 50%.</p>
 *
 * <p>Because this container is intended to better balance memory use, unlike most other
 * standard Java containers it will shrink its array as items are removed from it.  Currently
 * you have no control over this shrinking -- if you set a capacity and then remove an
 * item, it may reduce the capacity to better match the current size.  In the future an
 * explicit call to set the capacity should turn off this aggressive shrinking behavior.</p>
 */
/*
 * mHashes[] 和 mArray[] 里的元素在插入时是从索引0开始连续的，所以节省内存。
 * 在插入时按照key的hash值排好序
 *
 * 最大的优势就是节省内存，但是查找效率不如HashMap。
 *
 * 比HashMap节省内存的原因：
 * 1. 加入元素不需要额外创建一个Entry对象，而是直接放入mObjects数组
 * 2. 所有键值对都是连续紧凑的排列在mObjects数组中
 * 3. 删除元素的时候会将mObjects进行缩小，即将数组的length降低。
 *
 * 与HashMap的其他区别：
 * 1. 扩容的时候直接复制mObjects中的元素到新的数组中，而不需要像HashMap一样进行rehash
 */
public final class ArrayMap<K, V> implements Map<K, V> {
    private static final boolean DEBUG = false;
    private static final String TAG = "ArrayMap";

    /**
     * The minimum amount by which the capacity of a ArrayMap will increase.
     * This is tuned to be relatively space-efficient.
     */
    private static final int BASE_SIZE = 4;

    /**
     * Maximum number of entries to have in array caches.
     */
    private static final int CACHE_SIZE = 10;

    /**
     * @hide Special immutable empty ArrayMap.
     */
    public static final ArrayMap EMPTY = new ArrayMap(true);

    /**
     * Caches of small array objects to avoid spamming garbage.  The cache
     * Object[] variable is a pointer to a linked list of array objects.
     * The first entry in the array is a pointer to the next array in the
     * list; the second entry is a pointer to the int[] hash code array for it.
     */
    static Object[] mBaseCache;
    static int mBaseCacheSize;
    static Object[] mTwiceBaseCache;
    static int mTwiceBaseCacheSize;

    /**
     * Special hash array value that indicates the container is immutable.
     */
    static final int[] EMPTY_IMMUTABLE_INTS = new int[0];

    int[] mHashes;
    Object[] mArray;
    int mSize;
    MapCollections<K, V> mCollections;

    /*
     * 搜索的逻辑：
     * 1.查找hash值：没找到，说明map中没有这个key，直接插入新的key
     * 2.hash值相同，对比key本身是否相同（使用的是equals方法）：如果hash值相同，key相同，那么就直接在对应
     *   位置存入新的value
     * 3.hash值相同，key不同，返回相同hash值最后一个位置的下一个位置
     */
    int indexOf(Object key, int hash) {
        final int N = mSize;

        // Important fast case: if nothing is in here, nothing to look for.
        if (N == 0) {
            return ~0; // -1
        }

        int index = ContainerHelpers.binarySearch(mHashes, N, hash);

        // If the hash code wasn't found, then we have no entry for this key.
        if (index < 0) {
            return index;
        }

        // If the key at the returned index matches, that's what we want.
        if (key.equals(mArray[index<<1])) {
            return index;
        }

        /*
         * 这里为什么对上面二分查找的结果index前后都需要进行相同hash值的查找，是因为二分查找到的index，
         * 不一定是多个相同hash值中的哪一个，所以这个index前后可能都存在相同的hash值，进而需要前后都
         * 找一遍
         *
         * 向后查找：查找是否存在key和hash值都相同的情况。如果没找到，确定一个暂时的插入位置，如果向前查找
         *          仍然没找到，那么新的元素就会被插入到end位置。
         * 向前查找：只是查找是否存在key和hash都相同的情况，不会用来确定新元素的插入位置。
         */
        // Search for a matching key after the index.
        // 如果key的hash一样，但是key没有在mArray中，那么在相同hash值的下一个位置放置这个键值对
        int end;
        for (end = index + 1; end < N && mHashes[end] == hash; end++) {
            if (key.equals(mArray[end << 1])) return end;
        }

        // Search for a matching key before the index.
        for (int i = index - 1; i >= 0 && mHashes[i] == hash; i--) {
            if (key.equals(mArray[i << 1])) return i;
        }

        // Key not found -- return negative value indicating where a
        // new entry for this key should go.  We use the end of the
        // hash chain to reduce the number of array entries that will
        // need to be copied when inserting.
        return ~end;    // 注意，这里返回的是end，不是i（end是向后移动的最后位置，i是向前移动的位置）
    }

    /*
     * ArrayMap允许key=null，key为null时，使用0作为其hash值。但是注意，key不为null，hash值也可以是0，
     * 所以key为null的键值组合不一定保存在map的第一个位置。
     */
    int indexOfNull() {
        final int N = mSize;

        // Important fast case: if nothing is in here, nothing to look for.
        if (N == 0) {
            return ~0;
        }

        int index = ContainerHelpers.binarySearch(mHashes, N, 0);

        // If the hash code wasn't found, then we have no entry for this key.
        if (index < 0) {
            return index;
        }

        // If the key at the returned index matches, that's what we want.
        if (null == mArray[index<<1]) {
            return index;
        }

        // Search for a matching key after the index.
        int end;
        for (end = index + 1; end < N && mHashes[end] == 0; end++) {
            if (null == mArray[end << 1]) return end;
        }

        // Search for a matching key before the index.
        for (int i = index - 1; i >= 0 && mHashes[i] == 0; i--) {
            if (null == mArray[i << 1]) return i;
        }

        // Key not found -- return negative value indicating where a
        // new entry for this key should go.  We use the end of the
        // hash chain to reduce the number of array entries that will
        // need to be copied when inserting.
        return ~end;
    }

    /*
     * 1.创建的时候，有两个缓存池可以使用，缓存池中的ArrayMap的size=4 or 8，两个缓存池的数量上限都是10个。
     * 2.缓存池的实现比较巧妙，本质是一个链表结构，但是直接利用数组本身实现的链表结构。具体的实现可以
     *   看freeArrays()方法
     * 3.ArrayMap扩容的逻辑是只有在最必要的时候（已经满了）才扩容
     */
    private void allocArrays(final int size) {
        if (mHashes == EMPTY_IMMUTABLE_INTS) {
            throw new UnsupportedOperationException("ArrayMap is immutable");
        }
        if (size == (BASE_SIZE*2)) {
            synchronized (ArrayMap.class) {
                if (mTwiceBaseCache != null) {
                    final Object[] array = mTwiceBaseCache;
                    mArray = array;
                    mTwiceBaseCache = (Object[])array[0];
                    mHashes = (int[])array[1];
                    array[0] = array[1] = null;
                    mTwiceBaseCacheSize--;
                    if (DEBUG) Log.d(TAG, "Retrieving 2x cache " + mHashes
                            + " now have " + mTwiceBaseCacheSize + " entries");
                    return;
                }
            }
        } else if (size == BASE_SIZE) {
            synchronized (ArrayMap.class) {
                if (mBaseCache != null) {
                    final Object[] array = mBaseCache;
                    mArray = array;
                    mBaseCache = (Object[])array[0];
                    mHashes = (int[])array[1];
                    array[0] = array[1] = null;
                    mBaseCacheSize--;
                    if (DEBUG) Log.d(TAG, "Retrieving 1x cache " + mHashes
                            + " now have " + mBaseCacheSize + " entries");
                    return;
                }
            }
        }

        /*
         * 关于ArrayMap的初始化
         *
         * ArrayMap在创建的时候，如果不是4和8的容量，可以是用户设置的任意容量。也就是说硬要设置成容量为1，也是
         * 可以的。
         *
         * 因为ArrayMap默认缓存size为4和8的map对象，所以比起创建size为1~3的新map节省的一个元素的内存，直接
         * 使用缓存更效果更好，并且能够减少gc。而且既然缓存是全局的，放在那里不用，却重新创建一个新的，不论创建
         * 的新map容量有多小，也是在原有已消耗的内存上增加内存的使用量，所以不管怎么说都是先使用缓存比较靠谱。
         *
         * 还有一点是在clear()的时候，如果使用baseSize的map，那么释放的mHashes和mObjects数组可能会被回收，
         * 而其他size的数据会被释放掉。如果频繁的clear()->put()->clear()->put()，就会频繁的释放小对象，
         * 从而可能触发gc。而使用basesize的map，有助于减少gc。
         *
         * 还有一点可以看put(key,value)方法中对扩容的注释，说明没有确切的容量需求，可以直接使用默认的0个大小。
         */
        mHashes = new int[size];
        mArray = new Object[size<<1];
    }

    private static void freeArrays(final int[] hashes, final Object[] array, final int size) {
        if (hashes.length == (BASE_SIZE*2)) {
            synchronized (ArrayMap.class) {
                if (mTwiceBaseCacheSize < CACHE_SIZE) {
                    array[0] = mTwiceBaseCache;
                    array[1] = hashes;
                    // 放置到缓冲池时，只重置了array的元素。没有重置hashes数组，hashes中还保存着旧的hash值
                    // 使用对象主要是防止内存泄漏，因为缓冲池是静态的。hash值只是个int，没有必要重置，可以在
                    // 重新使用时再重置，这样相当于延迟不必要的操作，感觉也是懒加载的思想，到真正需要用的时候
                    // 再做需要的操作。
                    for (int i=(size<<1)-1; i>=2; i--) {
                        array[i] = null;
                    }
                    mTwiceBaseCache = array;
                    mTwiceBaseCacheSize++;
                    if (DEBUG) Log.d(TAG, "Storing 2x cache " + array
                            + " now have " + mTwiceBaseCacheSize + " entries");
                }
            }
        } else if (hashes.length == BASE_SIZE) {
            synchronized (ArrayMap.class) {
                if (mBaseCacheSize < CACHE_SIZE) {
                    array[0] = mBaseCache;
                    array[1] = hashes;
                    for (int i=(size<<1)-1; i>=2; i--) {
                        array[i] = null;
                    }
                    mBaseCache = array;
                    mBaseCacheSize++;
                    if (DEBUG) Log.d(TAG, "Storing 1x cache " + array
                            + " now have " + mBaseCacheSize + " entries");
                }
            }
        }
    }

    /**
     * Create a new empty ArrayMap.  The default capacity of an array map is 0, and
     * will grow once items are added to it.
     */
    public ArrayMap() {
        mHashes = EmptyArray.INT;
        mArray = EmptyArray.OBJECT;
        mSize = 0;
    }

    /**
     * Create a new ArrayMap with a given initial capacity.
     */
    public ArrayMap(int capacity) {
        if (capacity == 0) {
            mHashes = EmptyArray.INT;
            mArray = EmptyArray.OBJECT;
        } else {
            allocArrays(capacity);
        }
        mSize = 0;
    }

    private ArrayMap(boolean immutable) {
        // If this is immutable, use the sentinal EMPTY_IMMUTABLE_INTS
        // instance instead of the usual EmptyArray.INT. The reference
        // is checked later to see if the array is allowed to grow.
        mHashes = immutable ? EMPTY_IMMUTABLE_INTS : EmptyArray.INT;
        mArray = EmptyArray.OBJECT;
        mSize = 0;
    }

    /**
     * Create a new ArrayMap with the mappings from the given ArrayMap.
     */
    public ArrayMap(ArrayMap<K, V> map) {
        this();
        if (map != null) {
            putAll(map);
        }
    }

    /**
     * Make the array map empty.  All storage is released.
     */
    /*
     * ArrayMap的clear与Java通常的容器类不同，会直接释放底层的数组。如果不希望释放底层的数组，可以使用
     * erase方法。
     */
    @Override
    public void clear() {
        if (mSize > 0) {
            freeArrays(mHashes, mArray, mSize);
            mHashes = EmptyArray.INT;
            mArray = EmptyArray.OBJECT;
            mSize = 0;
        }
    }

    /**
     * @hide
     * Like {@link #clear}, but doesn't reduce the capacity of the ArrayMap.
     */
    public void erase() {
        if (mSize > 0) {
            final int N = mSize<<1;
            final Object[] array = mArray;
            for (int i=0; i<N; i++) {
                array[i] = null;
            }
            // 为什么没有清空mHashes[]数组？
            mSize = 0;
        }
    }

    /**
     * Ensure the array map can hold at least <var>minimumCapacity</var>
     * items.
     */
    public void ensureCapacity(int minimumCapacity) {
        if (mHashes.length < minimumCapacity) {
            final int[] ohashes = mHashes;
            final Object[] oarray = mArray;
            allocArrays(minimumCapacity);
            if (mSize > 0) {
                System.arraycopy(ohashes, 0, mHashes, 0, mSize);
                System.arraycopy(oarray, 0, mArray, 0, mSize<<1);
            }
            freeArrays(ohashes, oarray, mSize);
        }
    }

    /**
     * Check whether a key exists in the array.
     *
     * @param key The key to search for.
     * @return Returns true if the key exists, else false.
     */
    @Override
    public boolean containsKey(Object key) {
        return indexOfKey(key) >= 0;
    }

    /**
     * Returns the index of a key in the set.
     *
     * @param key The key to search for.
     * @return Returns the index of the key if it exists, else a negative integer.
     */
    public int indexOfKey(Object key) {
        return key == null ? indexOfNull() : indexOf(key, key.hashCode());
    }

    int indexOfValue(Object value) {
        final int N = mSize*2;
        final Object[] array = mArray;
        if (value == null) {
            for (int i=1; i<N; i+=2) {
                if (array[i] == null) {
                    return i>>1;
                }
            }
        } else {
            for (int i=1; i<N; i+=2) {
                if (value.equals(array[i])) {
                    return i>>1;
                }
            }
        }
        return -1;
    }

    /**
     * Check whether a value exists in the array.  This requires a linear search
     * through the entire array.
     *
     * @param value The value to search for.
     * @return Returns true if the value exists, else false.
     */
    @Override
    public boolean containsValue(Object value) {
        return indexOfValue(value) >= 0;
    }

    /**
     * Retrieve a value from the array.
     * @param key The key of the value to retrieve.
     * @return Returns the value associated with the given key,
     * or null if there is no such key.
     */
    @Override
    public V get(Object key) {
        final int index = indexOfKey(key);
        return index >= 0 ? (V)mArray[(index<<1)+1] : null;
    }

    /**
     * Return the key at the given index in the array.
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @return Returns the key stored at the given index.
     */
    public K keyAt(int index) {
        return (K)mArray[index << 1];
    }

    /**
     * Return the value at the given index in the array.
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @return Returns the value stored at the given index.
     */
    public V valueAt(int index) {
        return (V)mArray[(index << 1) + 1];
    }

    /**
     * Set the value at a given index in the array.
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @param value The new value to store at this index.
     * @return Returns the previous value at the given index.
     */
    public V setValueAt(int index, V value) {
        index = (index << 1) + 1;
        V old = (V)mArray[index];
        mArray[index] = value;
        return old;
    }

    /**
     * Return true if the array map contains no items.
     */
    @Override
    public boolean isEmpty() {
        return mSize <= 0;
    }

    /**
     * Add a new value to the array map.
     * @param key The key under which to store the value.  If
     * this key already exists in the array, its value will be replaced.
     * @param value The value to store for the given key.
     * @return Returns the old value that was stored for the given key, or null if there
     * was no such key.
     */
    /*
     * key=null,hash值取0
     */
    @Override
    public V put(K key, V value) {
        final int hash;
        int index;
        if (key == null) {
            hash = 0;
            index = indexOfNull();
        } else {
            hash = key.hashCode();
            /*
             * 如果hash冲突，index会返回相同hash值中最后一个位置的下一个位置
             * 比如当前插入key的hash值是250，如果mHashes中已经有hash值250了，那么不管有几个，当前要插入的
             * key的index都在mHash中最有一个250的下一个位置。
             */
            index = indexOf(key, hash);
        }
        // index>0，表示当前要插入的key已经在map中。
        if (index >= 0) {
            index = (index<<1) + 1;
            final V old = (V)mArray[index];
            mArray[index] = value;
            return old;
        }

        index = ~index;
        /*
         * 扩容的逻辑
         * 1.Array是当数组填满的时候进行才进行扩容，没有loadFactor。
         * 2.size小于4，扩容至4
         * 3.size小于8，扩容至8
         * 4.size大于8，扩容size的一半
         *
         * 从这里也可以看出，如果使用size小于8的map时，没有必要创建非4和8的map。因为如果创建了非4和8的map，
         * 比如size=3，第一次扩容就只会扩大到4，如果连续放入两个元素，相当于紧挨着进行了两次扩容操作，这一点
         * 就不是很好，所以也说明小容量最好还是直接使用4和8的初始容量。或者索性就不初始容量，因为0->4是直接
         * 复用BaseSize的缓存，而且因为0个元素，也没有复制数组的操作，所以没有性能损耗。
         */
        if (mSize >= mHashes.length) {
            final int n = mSize >= (BASE_SIZE*2) ? (mSize+(mSize>>1))
                    : (mSize >= BASE_SIZE ? (BASE_SIZE*2) : BASE_SIZE);

            if (DEBUG) Log.d(TAG, "put: grow from " + mHashes.length + " to " + n);

            final int[] ohashes = mHashes;
            final Object[] oarray = mArray;
            // 这里面将新的数组赋值给了mHashes和mArray
            allocArrays(n);

            /*
             * 这里即使是0->4的扩容，也需要执行copy操作，因为缓冲池中的hashes数组中保存着回收时旧的hash值。
             */
            if (mHashes.length > 0) {
                if (DEBUG) Log.d(TAG, "put: copy 0-" + mSize + " to 0");
                System.arraycopy(ohashes, 0, mHashes, 0, ohashes.length);
                System.arraycopy(oarray, 0, mArray, 0, oarray.length);
            }

            freeArrays(ohashes, oarray, mSize);
        }

        // 插入操作有可能涉及数组元素的后移，所以不适合大量的插入和删除操作
        if (index < mSize) {
            if (DEBUG) Log.d(TAG, "put: move " + index + "-" + (mSize-index)
                    + " to " + (index+1));
            System.arraycopy(mHashes, index, mHashes, index + 1, mSize - index);
            System.arraycopy(mArray, index << 1, mArray, (index + 1) << 1, (mSize - index) << 1);
        }

        mHashes[index] = hash;
        mArray[index<<1] = key;
        mArray[(index<<1)+1] = value;
        mSize++;
        return null;
    }

    /**
     * Special fast path for appending items to the end of the array without validation.
     * The array must already be large enough to contain the item.
     * @hide
     */
    /*
     * append操作同样需要满足hash值排序逻辑。只不过一上来不是先二分查找，而是会先判断当前插入的key的hash值是
     * 否比当前所有hash值都大，都大的话就不需要二分查找，直接放到最后即可。如果不是，重新调用put插入元素。
     */
    public void append(K key, V value) {
        int index = mSize;
        final int hash = key == null ? 0 : key.hashCode();
        if (index >= mHashes.length) {
            throw new IllegalStateException("Array is full");
        }
        if (index > 0 && mHashes[index-1] > hash) {
            RuntimeException e = new RuntimeException("here");
            e.fillInStackTrace();
            Log.w(TAG, "New hash " + hash
                    + " is before end of array hash " + mHashes[index-1]
                    + " at index " + index + " key " + key, e);
            put(key, value);
            return;
        }
        mSize = index+1;
        mHashes[index] = hash;
        index <<= 1;
        mArray[index] = key;
        mArray[index+1] = value;
    }

    /**
     * The use of the {@link #append} function can result in invalid array maps, in particular
     * an array map where the same key appears multiple times.  This function verifies that
     * the array map is valid, throwing IllegalArgumentException if a problem is found.  The
     * main use for this method is validating an array map after unpacking from an IPC, to
     * protect against malicious callers.
     * @hide
     */
    public void validate() {
        final int N = mSize;
        if (N <= 1) {
            // There can't be dups.
            return;
        }
        int basehash = mHashes[0];
        int basei = 0;
        for (int i=1; i<N; i++) {
            int hash = mHashes[i];
            if (hash != basehash) {
                basehash = hash;
                basei = i;
                continue;
            }
            // We are in a run of entries with the same hash code.  Go backwards through
            // the array to see if any keys are the same.
            final Object cur = mArray[i<<1];
            for (int j=i-1; j>=basei; j--) {
                final Object prev = mArray[j<<1];
                if (cur == prev) {
                    throw new IllegalArgumentException("Duplicate key in ArrayMap: " + cur);
                }
                if (cur != null && prev != null && cur.equals(prev)) {
                    throw new IllegalArgumentException("Duplicate key in ArrayMap: " + cur);
                }
            }
        }
    }

    /**
     * Perform a {@link #put(Object, Object)} of all key/value pairs in <var>array</var>
     * @param array The array whose contents are to be retrieved.
     */
    /*
     * putAll()有一点需要注意，如果当前map放不下这些新元素需要给当前map扩容，那么扩容之后的数组正好可以
     * 装满当前的所有元素，后续只要再插入任何元素，都需要立即扩容。
     * 这一点对于arraymap来说我认为是可以理解的，这种情况扩容基本是当前容量的一半，作为内存敏感的容器，
     * 的确没必要提前进行扩容，需要的时候再进行扩容即可。
     */
    public void putAll(ArrayMap<? extends K, ? extends V> array) {
        final int N = array.mSize;
        ensureCapacity(mSize + N);
        if (mSize == 0) {
            if (N > 0) {
                System.arraycopy(array.mHashes, 0, mHashes, 0, N);
                System.arraycopy(array.mArray, 0, mArray, 0, N<<1);
                mSize = N;
            }
        } else {
            // 因为涉及到每个key的重新排序，所以需要逐个加入，比较耗时。
            for (int i=0; i<N; i++) {
                put(array.keyAt(i), array.valueAt(i));
            }
        }
    }

    /**
     * Remove an existing key from the array map.
     * @param key The key of the mapping to remove.
     * @return Returns the value that was stored under the key, or null if there
     * was no such key.
     */
    @Override
    public V remove(Object key) {
        final int index = indexOfKey(key);
        if (index >= 0) {
            return removeAt(index);
        }

        return null;
    }

    /**
     * Remove the key/value mapping at the given index.
     * @param index The desired index, must be between 0 and {@link #size()}-1.
     * @return Returns the value that was stored at this index.
     */
    /*
     * 1.移除最后一个元素的时候，清空map，释放数组。如果移除的时候容量小于1/3，也会对容量进行缩小。

     */
    public V removeAt(int index) {
        final Object old = mArray[(index << 1) + 1];
        if (mSize <= 1) {
            // Now empty.
            if (DEBUG) Log.d(TAG, "remove: shrink from " + mHashes.length + " to 0");
            freeArrays(mHashes, mArray, mSize);
            mHashes = EmptyArray.INT;
            mArray = EmptyArray.OBJECT;
            mSize = 0;
        } else {
            if (mHashes.length > (BASE_SIZE*2) && mSize < mHashes.length/3) {
                // Shrunk enough to reduce size of arrays.  We don't allow it to
                // shrink smaller than (BASE_SIZE*2) to avoid flapping between
                // that and BASE_SIZE.
                /*
                 * 移除元素后size仍然大于8，那么就将当前数组缩小1/4；如果size小于8，不论当前数组的大小有
                 * 多大，都缩小到8
                 */
                final int n = mSize > (BASE_SIZE*2) ? (mSize + (mSize>>1)) : (BASE_SIZE*2);

                if (DEBUG) Log.d(TAG, "remove: shrink from " + mHashes.length + " to " + n);

                final int[] ohashes = mHashes;
                final Object[] oarray = mArray;
                allocArrays(n);

                mSize--;
                if (index > 0) {
                    if (DEBUG) Log.d(TAG, "remove: copy from 0-" + index + " to 0");
                    System.arraycopy(ohashes, 0, mHashes, 0, index);
                    System.arraycopy(oarray, 0, mArray, 0, index << 1);
                }
                if (index < mSize) {
                    if (DEBUG) Log.d(TAG, "remove: copy from " + (index+1) + "-" + mSize
                            + " to " + index);
                    System.arraycopy(ohashes, index + 1, mHashes, index, mSize - index);
                    System.arraycopy(oarray, (index + 1) << 1, mArray, index << 1,
                            (mSize - index) << 1);
                }
            } else {
                mSize--;
                if (index < mSize) {
                    if (DEBUG) Log.d(TAG, "remove: move " + (index+1) + "-" + mSize
                            + " to " + index);
                    System.arraycopy(mHashes, index + 1, mHashes, index, mSize - index);
                    System.arraycopy(mArray, (index + 1) << 1, mArray, index << 1,
                            (mSize - index) << 1);
                }
                mArray[mSize << 1] = null;
                mArray[(mSize << 1) + 1] = null;
            }
        }
        return (V)old;
    }

    /**
     * Return the number of items in this array map.
     */
    @Override
    public int size() {
        return mSize;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation returns false if the object is not a map, or
     * if the maps have different sizes. Otherwise, for each key in this map,
     * values of both maps are compared. If the values for any key are not
     * equal, the method returns false, otherwise it returns true.
     */
    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) object;
            if (size() != map.size()) {
                return false;
            }

            try {
                for (int i=0; i<mSize; i++) {
                    K key = keyAt(i);
                    V mine = valueAt(i);
                    Object theirs = map.get(key);
                    if (mine == null) {
                        if (theirs != null || !map.containsKey(key)) {
                            return false;
                        }
                    } else if (!mine.equals(theirs)) {
                        return false;
                    }
                }
            } catch (NullPointerException ignored) {
                return false;
            } catch (ClassCastException ignored) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int[] hashes = mHashes;
        final Object[] array = mArray;
        int result = 0;
        for (int i = 0, v = 1, s = mSize; i < s; i++, v+=2) {
            Object value = array[v];
            result += hashes[i] ^ (value == null ? 0 : value.hashCode());
        }
        return result;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation composes a string by iterating over its mappings. If
     * this map contains itself as a key or a value, the string "(this Map)"
     * will appear in its place.
     */
    @Override
    public String toString() {
        if (isEmpty()) {
            return "{}";
        }

        StringBuilder buffer = new StringBuilder(mSize * 28);
        buffer.append('{');
        for (int i=0; i<mSize; i++) {
            if (i > 0) {
                buffer.append(", ");
            }
            Object key = keyAt(i);
            if (key != this) {
                buffer.append(key);
            } else {
                buffer.append("(this Map)");
            }
            buffer.append('=');
            Object value = valueAt(i);
            if (value != this) {
                buffer.append(value);
            } else {
                buffer.append("(this Map)");
            }
        }
        buffer.append('}');
        return buffer.toString();
    }

    // ------------------------------------------------------------------------
    // Interop with traditional Java containers.  Not as efficient as using
    // specialized collection APIs.
    // ------------------------------------------------------------------------

    private MapCollections<K, V> getCollection() {
        if (mCollections == null) {
            mCollections = new MapCollections<K, V>() {
                @Override
                protected int colGetSize() {
                    return mSize;
                }

                @Override
                protected Object colGetEntry(int index, int offset) {
                    return mArray[(index<<1) + offset];
                }

                @Override
                protected int colIndexOfKey(Object key) {
                    return indexOfKey(key);
                }

                @Override
                protected int colIndexOfValue(Object value) {
                    return indexOfValue(value);
                }

                @Override
                protected Map<K, V> colGetMap() {
                    return ArrayMap.this;
                }

                @Override
                protected void colPut(K key, V value) {
                    put(key, value);
                }

                @Override
                protected V colSetValue(int index, V value) {
                    return setValueAt(index, value);
                }

                @Override
                protected void colRemoveAt(int index) {
                    removeAt(index);
                }

                @Override
                protected void colClear() {
                    clear();
                }
            };
        }
        return mCollections;
    }

    /**
     * Determine if the array map contains all of the keys in the given collection.
     * @param collection The collection whose contents are to be checked against.
     * @return Returns true if this array map contains a key for every entry
     * in <var>collection</var>, else returns false.
     */
    public boolean containsAll(Collection<?> collection) {
        return MapCollections.containsAllHelper(this, collection);
    }

    /**
     * Perform a {@link #put(Object, Object)} of all key/value pairs in <var>map</var>
     * @param map The map whose contents are to be retrieved.
     */
    @Override
    public void putAll(Map<? extends K, ? extends V> map) {
        ensureCapacity(mSize + map.size());
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Remove all keys in the array map that exist in the given collection.
     * @param collection The collection whose contents are to be used to remove keys.
     * @return Returns true if any keys were removed from the array map, else false.
     */
    public boolean removeAll(Collection<?> collection) {
        return MapCollections.removeAllHelper(this, collection);
    }

    /**
     * Remove all keys in the array map that do <b>not</b> exist in the given collection.
     * @param collection The collection whose contents are to be used to determine which
     * keys to keep.
     * @return Returns true if any keys were removed from the array map, else false.
     */
    public boolean retainAll(Collection<?> collection) {
        return MapCollections.retainAllHelper(this, collection);
    }

    /**
     * Return a {@link java.util.Set} for iterating over and interacting with all mappings
     * in the array map.
     *
     * <p><b>Note:</b> this is a very inefficient way to access the array contents, it
     * requires generating a number of temporary objects and allocates additional state
     * information associated with the container that will remain for the life of the container.</p>
     *
     * <p><b>Note:</b></p> the semantics of this
     * Set are subtly different than that of a {@link java.util.HashMap}: most important,
     * the {@link java.util.Map.Entry Map.Entry} object returned by its iterator is a single
     * object that exists for the entire iterator, so you can <b>not</b> hold on to it
     * after calling {@link java.util.Iterator#next() Iterator.next}.</p>
     */
    @Override
    public Set<Map.Entry<K, V>> entrySet() {
        return getCollection().getEntrySet();
    }

    /**
     * Return a {@link java.util.Set} for iterating over and interacting with all keys
     * in the array map.
     *
     * <p><b>Note:</b> this is a fairly inefficient way to access the array contents, it
     * requires generating a number of temporary objects and allocates additional state
     * information associated with the container that will remain for the life of the container.</p>
     */
    @Override
    public Set<K> keySet() {
        return getCollection().getKeySet();
    }

    /**
     * Return a {@link java.util.Collection} for iterating over and interacting with all values
     * in the array map.
     *
     * <p><b>Note:</b> this is a fairly inefficient way to access the array contents, it
     * requires generating a number of temporary objects and allocates additional state
     * information associated with the container that will remain for the life of the container.</p>
     */
    @Override
    public Collection<V> values() {
        return getCollection().getValues();
    }
}
