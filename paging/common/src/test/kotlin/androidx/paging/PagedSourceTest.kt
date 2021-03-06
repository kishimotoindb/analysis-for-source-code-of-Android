/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.paging

import androidx.paging.PagedSource.LoadParams
import androidx.paging.PagedSource.LoadResult
import androidx.paging.PagedSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertFailsWith

@RunWith(JUnit4::class)
class PagedSourceTest {

    // ----- STANDARD -----

    private suspend fun loadInitial(
        pagedSource: ItemDataSource,
        key: Key?,
        initialLoadSize: Int,
        enablePlaceholders: Boolean
    ): LoadResult<Key, Item> {
        return pagedSource.load(
            LoadParams(
                LoadType.REFRESH,
                key,
                initialLoadSize,
                enablePlaceholders,
                10
            )
        )
    }

    @Test
    fun loadInitial() {
        runBlocking {
            val pagedSource = ItemDataSource()
            val key = ITEMS_BY_NAME_ID[49].key()
            val result = loadInitial(pagedSource, key, 10, true) as LoadResult.Page

            assertEquals(45, result.itemsBefore)
            assertEquals(ITEMS_BY_NAME_ID.subList(45, 55), result.data)
            assertEquals(45, result.itemsAfter)

            // Verify error is propagated correctly.
            pagedSource.enqueueError()
            val errorParams = LoadParams(LoadType.REFRESH, key, 10, false, 10)
            assertFailsWith<CustomException> {
                pagedSource.load(errorParams)
            }
        }
    }

    @Test
    fun loadInitial_keyMatchesSingleItem() = runBlocking {
        val pagedSource = ItemDataSource(items = ITEMS_BY_NAME_ID.subList(0, 1))

        // this is tricky, since load after and load before with the passed key will fail
        val result =
            loadInitial(pagedSource, ITEMS_BY_NAME_ID[0].key(), 20, true) as LoadResult.Page

        assertEquals(0, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(0, 1), result.data)
        assertEquals(0, result.itemsAfter)
    }

    @Test
    fun loadInitial_keyMatchesLastItem() = runBlocking {
        val pagedSource = ItemDataSource()

        // tricky, because load after key is empty, so another load before and load after required
        val key = ITEMS_BY_NAME_ID.last().key()
        val result = loadInitial(pagedSource, key, 20, true) as LoadResult.Page

        assertEquals(90, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(90, 100), result.data)
        assertEquals(0, result.itemsAfter)
    }

    @Test
    fun loadInitial_nullKey() = runBlocking {
        val dataSource = ItemDataSource()

        val result = loadInitial(dataSource, null, 10, true) as LoadResult.Page

        assertEquals(0, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(0, 10), result.data)
        assertEquals(90, result.itemsAfter)
    }

    @Test
    fun loadInitial_keyPastEndOfList() = runBlocking {
        val dataSource = ItemDataSource()

        // if key is past entire data set, should return last items in data set
        val key = Key("fz", 0)
        val result = loadInitial(dataSource, key, 10, true) as LoadResult.Page

        // NOTE: ideally we'd load 10 items here, but it adds complexity and unpredictability to
        // do: load after was empty, so pass full size to load before, since this can incur larger
        // loads than requested (see keyMatchesLastItem test)
        assertEquals(95, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(95, 100), result.data)
        assertEquals(0, result.itemsAfter)
    }

    // ----- UNCOUNTED -----

    @Test
    fun loadInitial_disablePlaceholders() = runBlocking {
        val dataSource = ItemDataSource()

        // dispatchLoadInitial(key, count) == null padding, loadAfter(key, count), null padding
        val key = ITEMS_BY_NAME_ID[49].key()
        val result = loadInitial(dataSource, key, 10, false) as LoadResult.Page

        assertEquals(COUNT_UNDEFINED, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(45, 55), result.data)
        assertEquals(COUNT_UNDEFINED, result.itemsAfter)
    }

    @Test
    fun loadInitial_uncounted() = runBlocking {
        val dataSource = ItemDataSource(counted = false)

        // dispatchLoadInitial(key, count) == null padding, loadAfter(key, count), null padding
        val key = ITEMS_BY_NAME_ID[49].key()
        val result = loadInitial(dataSource, key, 10, true) as LoadResult.Page

        assertEquals(COUNT_UNDEFINED, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(45, 55), result.data)
        assertEquals(COUNT_UNDEFINED, result.itemsAfter)
    }

    @Test
    fun loadInitial_nullKey_uncounted() = runBlocking {
        val dataSource = ItemDataSource(counted = false)

        val result = loadInitial(dataSource, null, 10, true) as LoadResult.Page

        assertEquals(COUNT_UNDEFINED, result.itemsBefore)
        assertEquals(ITEMS_BY_NAME_ID.subList(0, 10), result.data)
        assertEquals(COUNT_UNDEFINED, result.itemsAfter)
    }

    // ----- EMPTY -----

    @Test
    fun loadInitial_empty() = runBlocking {
        val dataSource = ItemDataSource(items = ArrayList())

        // dispatchLoadInitial(key, count) == null padding, loadAfter(key, count), null padding
        val key = ITEMS_BY_NAME_ID[49].key()
        val result = loadInitial(dataSource, key, 10, true) as LoadResult.Page

        assertEquals(0, result.itemsBefore)
        assertTrue(result.data.isEmpty())
        assertEquals(0, result.itemsAfter)
    }

    @Test
    fun loadInitial_nullKey_empty() = runBlocking {
        val dataSource = ItemDataSource(items = ArrayList())
        val result = loadInitial(dataSource, null, 10, true) as LoadResult.Page

        assertEquals(0, result.itemsBefore)
        assertTrue(result.data.isEmpty())
        assertEquals(0, result.itemsAfter)
    }

    // ----- Other behavior -----

    @Test
    fun loadBefore() {
        val dataSource = ItemDataSource()

        runBlocking {
            val key = ITEMS_BY_NAME_ID[5].key()
            val params = LoadParams(LoadType.START, key, 5, false, 5)
            val observed = (dataSource.load(params) as LoadResult.Page).data

            assertEquals(ITEMS_BY_NAME_ID.subList(0, 5), observed)

            // Verify error is propagated correctly.
            dataSource.enqueueError()
            assertFailsWith<CustomException> {
                val errorParams = LoadParams(LoadType.START, key, 5, false, 5)
                dataSource.load(errorParams)
            }
        }
    }

    @Test
    fun loadAfter() {
        val dataSource = ItemDataSource()

        runBlocking {
            val key = ITEMS_BY_NAME_ID[5].key()
            val params = LoadParams(LoadType.END, key, 5, false, 5)
            val observed = (dataSource.load(params) as LoadResult.Page).data

            assertEquals(ITEMS_BY_NAME_ID.subList(6, 11), observed)

            // Verify error is propagated correctly.
            dataSource.enqueueError()
            assertFailsWith<CustomException> {
                val errorParams = LoadParams(LoadType.END, key, 5, false, 5)
                dataSource.load(errorParams)
            }
        }
    }

    data class Key(val name: String, val id: Int)

    data class Item(
        val name: String,
        val id: Int,
        val balance: Double,
        val address: String
    )

    fun Item.key() = Key(name, id)

    internal class ItemDataSource(
        private val counted: Boolean = true,
        private val items: List<Item> = ITEMS_BY_NAME_ID
    ) : PagedSource<Key, Item>() {
        fun Item.key() = Key(name, id)

        private fun List<Item>.asPage(
            itemsBefore: Int = COUNT_UNDEFINED,
            itemsAfter: Int = COUNT_UNDEFINED
        ): LoadResult.Page<Key, Item> = LoadResult.Page(
            data = this,
            prevKey = firstOrNull()?.key(),
            nextKey = lastOrNull()?.key(),
            itemsBefore = itemsBefore,
            itemsAfter = itemsAfter
        )

        override fun getRefreshKeyFromPage(
            indexInPage: Int,
            page: LoadResult.Page<Key, Item>
        ): Key? {
            val item = page.data[indexInPage]
            return Key(item.name, item.id)
        }

        private var error = false

        override suspend fun load(params: LoadParams<Key>): LoadResult<Key, Item> {
            return when (params.loadType) {
                LoadType.REFRESH -> loadInitial(params)
                LoadType.START -> loadBefore(params)
                LoadType.END -> loadAfter(params)
            }
        }

        private fun loadInitial(params: LoadParams<Key>): LoadResult<Key, Item> {
            if (error) {
                error = false
                throw EXCEPTION
            }

            val key = params.key ?: Key("", Int.MAX_VALUE)
            val start = maxOf(0, findFirstIndexAfter(key) - params.loadSize / 2)
            val endExclusive = minOf(start + params.loadSize, items.size)

            return if (params.placeholdersEnabled && counted) {
                val data = items.subList(start, endExclusive)
                data.asPage(start, items.size - data.size - start)
            } else {
                items.subList(start, endExclusive).asPage()
            }
        }

        private fun loadAfter(params: LoadParams<Key>): LoadResult<Key, Item> {
            if (error) {
                error = false
                throw EXCEPTION
            }

            val start = findFirstIndexAfter(params.key!!)
            val endExclusive = minOf(start + params.loadSize, items.size)

            return items.subList(start, endExclusive).asPage()
        }

        private fun loadBefore(params: LoadParams<Key>): LoadResult<Key, Item> {
            if (error) {
                error = false
                throw EXCEPTION
            }

            val firstIndexBefore = findFirstIndexBefore(params.key!!)
            val endExclusive = maxOf(0, firstIndexBefore + 1)
            val start = maxOf(0, firstIndexBefore - params.loadSize + 1)
            return items.subList(start, endExclusive).asPage()
        }

        private fun findFirstIndexAfter(key: Key): Int {
            return items.indices.firstOrNull {
                KEY_COMPARATOR.compare(key, items[it].key()) < 0
            } ?: items.size
        }

        private fun findFirstIndexBefore(key: Key): Int {
            return items.indices.reversed().firstOrNull {
                KEY_COMPARATOR.compare(key, items[it].key()) > 0
            } ?: -1
        }

        fun enqueueError() {
            error = true
        }
    }

    class CustomException : Exception()

    companion object {
        private val ITEM_COMPARATOR = compareBy<Item> { it.name }.thenByDescending { it.id }
        private val KEY_COMPARATOR = compareBy<Key> { it.name }.thenByDescending { it.id }

        private val ITEMS_BY_NAME_ID = List(100) {
            val names = Array(10) { index -> "f" + ('a' + index) }
            Item(
                names[it % 10],
                it,
                Math.random() * 1000,
                (Math.random() * 200).toInt().toString() + " fake st."
            )
        }.sortedWith(ITEM_COMPARATOR)

        private val EXCEPTION = CustomException()
    }
}
