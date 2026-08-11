package com.sysadmin.lasstore.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LibraryStoreRobolectricTest {
    private lateinit var store: LibraryStore

    @Before
    fun setUp() {
        store = LibraryStore(ApplicationProvider.getApplicationContext()).also { it.clear() }
    }

    @Test
    fun favoritesAndCollectionsPersistAndFollowPackageIdentity() {
        val sourceKeys = libraryKeysFor(null, "github:owner", "owner", "app")
        val packageKeys = libraryKeysFor("com.example.app", "github:owner", "owner", "app")
        val collection = store.createCollection("Watch later")!!

        assertTrue(store.toggleFavorite(sourceKeys))
        store.setCollections(sourceKeys, setOf(collection.id))

        val reloaded = LibraryStore(ApplicationProvider.getApplicationContext())
        assertTrue(reloaded.isFavorite(packageKeys))
        assertEquals(setOf(collection.id), reloaded.collectionIds(packageKeys))

        assertFalse(reloaded.toggleFavorite(packageKeys))
        assertFalse(reloaded.isFavorite(sourceKeys))
        assertEquals(listOf(collection), reloaded.collections())
    }

    @Test
    fun deletingCollectionRemovesMembershipButKeepsFavorite() {
        val key = libraryKeysFor("com.example.app", "source", "owner", "app")
        val collection = store.createCollection("Installed")!!
        store.toggleFavorite(key)
        store.setCollections(key, setOf(collection.id))

        store.deleteCollection(collection.id)

        assertTrue(store.isFavorite(key))
        assertTrue(store.collectionIds(key).isEmpty())
        assertTrue(store.collections().isEmpty())
    }

    @Test
    fun importedLibraryMergesCollectionsByNameAndPreservesLocalFavorites() {
        val local = store.createCollection("Watch later")!!
        val localKey = libraryKeysFor("com.example.local", "source", "owner", "local")
        store.toggleFavorite(localKey)

        val importedKey = libraryKeysFor("com.example.imported", "source", "owner", "imported")
        val result = store.merge(
            LibrarySnapshot(
                collections = listOf(
                    LibraryCollectionSnapshot("other-id", "Watch later"),
                    LibraryCollectionSnapshot("new-id", "Read later"),
                ),
                entries = listOf(
                    LibraryEntrySnapshot(
                        key = importedKey.first(),
                        favorite = true,
                        collectionIds = setOf("other-id", "new-id"),
                    ),
                ),
            ),
        )

        assertEquals(1, result.collectionsAdded)
        assertTrue(store.isFavorite(localKey))
        assertTrue(store.isFavorite(importedKey))
        assertEquals(2, store.collections().size)
        assertTrue(store.collectionIds(importedKey).contains(local.id))
    }
}
