package com.magicbell.sdk.feature.store

import androidx.annotation.VisibleForTesting
import com.magicbell.sdk.common.error.MagicBellError
import com.magicbell.sdk.common.network.graphql.CursorPredicate
import com.magicbell.sdk.common.network.graphql.CursorPredicate.Cursor.Next
import com.magicbell.sdk.common.network.graphql.Edge
import com.magicbell.sdk.common.query.UserQuery
import com.magicbell.sdk.common.threading.MainThread
import com.magicbell.sdk.feature.notification.Notification
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery.Action.ARCHIVE
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery.Action.MARK_ALL_AS_READ
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery.Action.MARK_ALL_AS_SEEN
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery.Action.MARK_AS_READ
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery.Action.MARK_AS_UNREAD
import com.magicbell.sdk.feature.notification.data.NotificationActionQuery.Action.UNARCHIVE
import com.magicbell.sdk.feature.notification.interactor.ActionNotificationInteractor
import com.magicbell.sdk.feature.notification.interactor.DeleteNotificationInteractor
import com.magicbell.sdk.feature.realtime.StoreRealTimeNotificationChange
import com.magicbell.sdk.feature.realtime.StoreRealTimeObserver
import com.magicbell.sdk.feature.store.interactor.FetchStorePageInteractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date
import java.util.WeakHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The NotificationStore class represents a collection of MagicBell notifications.
 */
class NotificationStore internal constructor(
  val predicate: StorePredicate,
  private val coroutineContext: CoroutineContext,
  private val mainThread: MainThread,
  private val userQuery: UserQuery,
  private val fetchStorePageInteractor: FetchStorePageInteractor,
  private val actionNotificationInteractor: ActionNotificationInteractor,
  private val deleteNotificationInteractor: DeleteNotificationInteractor,
) : List<Notification> {

  internal val realTimeObserver = object : StoreRealTimeObserver {
    override fun notifyNewNotification(id: String) {
      refreshAndNotifyObservers()
    }

    override fun notifyDeleteNotification(id: String) {
      val notificationIndex = edges.indexOfFirst { it.node.id == id }
      if (notificationIndex != -1) {
        updateCountersWhenDelete(edges[notificationIndex].node, predicate)
        edges.removeAt(notificationIndex)
        forEachContentObserver { it.onNotificationsDeleted(listOf(notificationIndex)) }
      }
    }

    override fun notifyNotificationChange(id: String, change: StoreRealTimeNotificationChange) {
      val notificationIndex = edges.indexOfFirst { it.node.id == id }
      if (notificationIndex != -1) {
        val notification = edges[notificationIndex].node
        when (change) {
          StoreRealTimeNotificationChange.READ -> markNotificationAsRead(notification, predicate)
          StoreRealTimeNotificationChange.UNREAD -> markNotificationAsUnread(notification, predicate)
          StoreRealTimeNotificationChange.ARCHIVED -> archiveNotification(notification, predicate)
        }
        if (predicate.match(notification)) {
          edges[notificationIndex].node = notification
          forEachContentObserver { it.onNotificationsChanged(listOf(notificationIndex)) }
        } else {
          edges.removeAt(notificationIndex)
          forEachContentObserver { it.onNotificationsDeleted(listOf(notificationIndex)) }
        }
      } else {
        refreshAndNotifyObservers()
      }
    }

    override fun notifyAllNotificationRead() {
      if (predicate.read == null || predicate.read == true) {
        refreshAndNotifyObservers()
      } else {
        clear(true)
      }
    }

    override fun notifyAllNotificationSeen() {
      if (predicate.seen == null || predicate.seen == true) {
        refreshAndNotifyObservers()
      } else {
        clear(true)
      }
    }

    override fun notifyReloadStore() {
      refreshAndNotifyObservers()
    }
  }

  private fun refreshAndNotifyObservers() {
    CoroutineScope(Dispatchers.Main).launch {
      refresh()
    }
  }

  private val pageSize = 20

  private val edges: MutableList<Edge<Notification>> = mutableListOf()

  var totalCount: Int = 0
    private set
  var unreadCount: Int = 0
    private set
  var unseenCount: Int = 0
    private set
  var hasNextPage: Boolean = true
    private set

  private var nextPageCursor: String? = null

  @VisibleForTesting
  private val contentObservers = WeakHashMap<NotificationStoreContentObserver, NotificationStoreContentObserver>()
  @VisibleForTesting
  private val countObservers = WeakHashMap<NotificationStoreCountObserver, NotificationStoreCountObserver>()

  private fun setTotalCount(value: Int, notifyObservers: Boolean) {
    val oldValue = totalCount
    totalCount = value
    if (oldValue != totalCount && notifyObservers) {
      forEachCountObserver { it.onTotalCountChanged(totalCount) }
    }
  }

  private fun setUnreadCount(value: Int, notifyObservers: Boolean) {
    val oldValue = unreadCount
    unreadCount = value
    if (oldValue != unreadCount && notifyObservers) {
      forEachCountObserver { it.onUnreadCountChanged(unreadCount) }
    }
  }

  private fun setUnseenCount(value: Int, notifyObservers: Boolean) {
    val oldValue = unseenCount
    unseenCount = value
    if (oldValue != unseenCount && notifyObservers) {
      forEachCountObserver { it.onUnseenCountChanged(unseenCount) }
    }
  }

  private fun setHasNextPage(value: Boolean) {
    val oldValue = hasNextPage
    hasNextPage = value
    if (oldValue != hasNextPage) {
      forEachContentObserver { it.onStoreHasNextPageChanged(hasNextPage) }
    }
  }

  /**
   * Number of notifications loaded in the store
   */
  val count: Int = edges.count()

  /**
   * Returns a list containing all notifications
   */
  val notifications: List<Notification>
    get() {
      return edges.map {
        it.node
      }
    }

  override val size: Int
    get() {
      return edges.size
    }

  override fun get(index: Int): Notification {
    return edges[index].node
  }

  override fun contains(element: Notification): Boolean {
    return edges.firstOrNull { it.node.id == element.id } != null
  }

  override fun containsAll(elements: Collection<Notification>): Boolean = notifications.containsAll(elements)

  override fun isEmpty(): Boolean = edges.isEmpty()

  override fun iterator(): Iterator<Notification> = notifications.iterator()

  override fun indexOf(element: Notification): Int {
    return edges.indexOfFirst { it.node.id == element.id }
  }

  override fun lastIndexOf(element: Notification): Int {
    return edges.indexOfLast { it.node.id == element.id }
  }

  override fun listIterator(): ListIterator<Notification> = notifications.listIterator()

  override fun listIterator(index: Int): ListIterator<Notification> = notifications.listIterator(index)

  override fun subList(fromIndex: Int, toIndex: Int): List<Notification> {
    return edges.subList(fromIndex, toIndex).map { it.node }
  }

  //region Observers
  /**
   * Adds a content observer.
   *
   * @param observer The observer
   */
  fun addContentObserver(observer: NotificationStoreContentObserver) {
    contentObservers[observer] = observer
  }

  /**
   * Removes a content observer.
   *
   * @param observer The observer
   */
  fun removeContentObserver(observer: NotificationStoreContentObserver) {
    contentObservers.remove(observer)
  }

  /**
   * Adds a count observer.
   *
   * @param observer The observer
   */
  fun addCountObserver(observer: NotificationStoreCountObserver) {
    countObservers[observer] = observer
  }

  /**
   * Removes a count observer.
   *
   * @param observer The observer
   */
  fun removeCountObserver(observer: NotificationStoreCountObserver) {
    countObservers.remove(observer)
  }

  private fun forEachContentObserver(action: (NotificationStoreContentObserver) -> Unit) {
    mainThread.post {
      contentObservers.values.forEach { observer ->
        action(observer)
      }
    }
  }

  private fun forEachCountObserver(action: (NotificationStoreCountObserver) -> Unit) {
    mainThread.post {
      countObservers.values.forEach { observer ->
        action(observer)
      }
    }
  }
  //endregion

  /**
   * Clears the store and fetches first page.
   *
   * @return A Result with the list of notifications.
   */
  suspend fun refresh(): Result<List<Notification>> {
    return runCatching {
      runBlocking {
        val cursorPredicate = CursorPredicate(size = pageSize)
        val storePage = fetchStorePageInteractor(predicate, cursorPredicate, userQuery)
        clear(false)
        configurePagination(storePage)
        configureCount(storePage)
        val newEdges = storePage.edges
        edges.addAll(newEdges)
        val notifications = newEdges.map { it.node }
        forEachContentObserver { it.onStoreReloaded() }
        notifications
      }
    }
  }

  /**
   * Fetches the next page of notifications. It can be called multiple times to obtain all pages.
   * This method will notify the observers if changes are made into the store.
   *
   * @return A Result with the list of notifications.
   */
  suspend fun fetch(): Result<List<Notification>> {
    return runCatching {
      runBlocking {
        if (!hasNextPage) {
          return@runBlocking listOf<Notification>()
        }
        val cursorPredicate: CursorPredicate = nextPageCursor?.let { after ->
          CursorPredicate(Next(after), pageSize)
        } ?: run {
          CursorPredicate(size = pageSize)
        }

        val storePage = fetchStorePageInteractor(predicate, cursorPredicate, userQuery)
        configurePagination(storePage)
        configureCount(storePage)

        val oldCount = edges.count()
        val newEdges = storePage.edges
        edges.addAll(newEdges)
        val notifications = newEdges.map { it.node }
        val indexes = oldCount until edges.size
        forEachContentObserver { it.onNotificationsInserted(indexes.toList()) }
        notifications
      }
    }
  }

  /**
   * Deletes a notification.
   *
   * @param notification The notification.
   * @return A Result with the error if exists.
   */
  suspend fun delete(notification: Notification): Result<Unit> {
    return runCatching {
      deleteNotificationInteractor(notification.id, userQuery).toString()
      val notificationIndex = edges.indexOfFirst { it.node.id == notification.id }
      if (notificationIndex != -1) {
        updateCountersWhenDelete(edges[notificationIndex].node, predicate)
        edges.removeAt(notificationIndex)
        forEachContentObserver { it.onNotificationsDeleted(listOf(notificationIndex)) }
      }
    }
  }

  /**
   * Marks a notification as read.
   *
   * @param notification The notification.
   * @return A Result with the modified notification.
   */
  suspend fun markAsRead(notification: Notification): Result<Notification> {
    return runCatching {
      executeNotificationAction(
        notification,
        MARK_AS_READ,
        modifications = { notification ->
          markNotificationAsRead(notification, predicate)
        }).getOrThrow()
    }
  }

  /**
   * Marks a notification as unread.
   *
   * @param notification The notification.
   * @return A Result with the modified notification.
   */
  suspend fun markAsUnread(notification: Notification): Result<Notification> {
    return runCatching {
      executeNotificationAction(
        notification,
        MARK_AS_UNREAD,
        modifications = { notification ->
          markNotificationAsUnread(notification, predicate)
        }).getOrThrow()
    }
  }

  /**
   * Marks a notification as archived.
   *
   * @param notification The notification.
   * @return A Result with the modified notification.
   */
  suspend fun archive(notification: Notification): Result<Notification> {
    return runCatching {
      executeNotificationAction(
        notification,
        ARCHIVE,
        modifications = { notification ->
          archiveNotification(notification, predicate)
        }).getOrThrow()
    }
  }

  /**
   * Marks a notification as unarchived.
   *
   * @param notification The notification.
   * @return A Result with the modified notification.
   */
  suspend fun unarchive(notification: Notification): Result<Notification> {
    return runCatching {
      executeNotificationAction(
        notification,
        UNARCHIVE,
        modifications = { notification ->
          notification.archivedAt = null
        }).getOrThrow()
    }
  }

  /**
   * Marks all notifications as read.
   *
   * @return A Result with the error if exists.
   */
  suspend fun markAllNotificationAsRead(): Result<Unit> {
    return runCatching {
      executeAllNotificationsAction(
        MARK_ALL_AS_READ,
        modifications = { notification ->
          markNotificationAsRead(notification, predicate)
        }).getOrThrow()
    }
  }

  /**
   * Marks all notifications as seen.
   *
   * @return A Result with the error if exists.
   */
  suspend fun markAllNotificationAsSeen(): Result<Unit> {
    return runCatching {
      executeAllNotificationsAction(
        MARK_ALL_AS_SEEN,
        modifications = { notification ->
          if (notification.seenAt == null) {
            notification.seenAt = Date()
            unseenCount -= 1
          }
        }).getOrThrow()
    }
  }

  //region Private methods
  //region NotificationActions
  private fun clear(notifyChanges: Boolean) {
    val notificationCount = size
    edges.clear()
    setTotalCount(0, notifyChanges)
    setUnreadCount(0, notifyChanges)
    setUnseenCount(0, notifyChanges)
    nextPageCursor = null
    setHasNextPage(true)
    if (notifyChanges) {
      val indexes = 0 until notificationCount
      forEachContentObserver { it.onNotificationsDeleted(indexes.toList()) }
    }
  }

  private suspend fun executeNotificationAction(
    notification: Notification,
    action: NotificationActionQuery.Action,
    modifications: (Notification) -> Unit,
  ): Result<Notification> {
    return runCatching {
      actionNotificationInteractor(action, notification.id, userQuery)
      val notificationIndex = edges.indexOfFirst { it.node.id == notification.id }
      if (notificationIndex != -1) {
        modifications(notification)
        notification
      } else {
        throw MagicBellError("Notification not found in Store")
      }
    }
  }

  private suspend fun executeAllNotificationsAction(
    action: NotificationActionQuery.Action,
    modifications: (Notification) -> Unit,
  ): Result<Unit> {
    return runCatching {
      actionNotificationInteractor(action, userQuery = userQuery)
      for (i in edges.indices) {
        modifications(edges[i].node)
      }
    }
  }

  private fun configurePagination(storePage: StorePage) {
    val pageInfo = storePage.pageInfo
    nextPageCursor = pageInfo.endCursor
    setHasNextPage(pageInfo.hasNextPage)
  }

  private fun configureCount(storePage: StorePage) {
    setTotalCount(storePage.totalCount, true)
    setUnreadCount(storePage.unreadCount, true)
    setUnseenCount(storePage.unseenCount, true)
  }

  private fun markNotificationAsRead(notification: Notification, storePredicate: StorePredicate) {
    if (notification.seenAt == null) {
      setUnseenCount(unseenCount - 1, true)
    }

    if (notification.readAt == null) {
      setUnreadCount(unreadCount - 1, true)

      storePredicate.read?.also { isRead ->
        if (isRead) {
          setTotalCount(totalCount - 1, true)
        } else {
          setTotalCount(totalCount - 1, true)
        }
      }
    }

    val now = Date()
    notification.readAt = now
    notification.seenAt = now
  }

  private fun markNotificationAsUnread(notification: Notification, storePredicate: StorePredicate) {
    if (notification.readAt != null) {
      storePredicate.read?.also {
        if (it) {
          setTotalCount(totalCount - 1, true)
          setUnreadCount(0, true)
        } else {
          setTotalCount(totalCount + 1, true)
          setUnreadCount(unreadCount + 1, true)
        }
      } ?: run {
        setUnreadCount(unreadCount + 1, true)
      }
    }

    notification.readAt = null
  }

  private fun archiveNotification(notification: Notification, storePredicate: StorePredicate) {
    if (notification.archivedAt != null) {
      return
    }

    if (notification.seenAt == null) {
      setUnseenCount(unseenCount - 1, true)
    }

    if (notification.readAt == null) {
      setUnreadCount(unreadCount - 1, true)
    }

    if (notification.archivedAt == null) {
      if (!storePredicate.archived) {
        setTotalCount(totalCount - 1, true)
      }
    }

    notification.archivedAt = Date()
  }

  //endregion

  //region Counter methods
  private fun updateCountersWhenDelete(notification: Notification, predicate: StorePredicate) {
    setTotalCount(totalCount - 1, true)

    decreaseUnreadCountIfUnreadPredicate(predicate, notification)
    decreaseUnseenCountIfNotificationWasUnseen(notification)
  }

  private fun decreaseUnreadCountIfUnreadPredicate(predicate: StorePredicate, notification: Notification) {
    if (predicate.read != null) {
      if (predicate.read == false) {
        setUnreadCount(unreadCount - 1, true)
      }
    } else {
      notification.readAt.also {
        setUnreadCount(unreadCount - 1, true)
      }
    }
  }

  private fun decreaseUnseenCountIfNotificationWasUnseen(notification: Notification) {
    if (notification.seenAt == null) {
      setUnseenCount(unseenCount - 1, true)
    }
  }
  //endregion
  //endregion
}
