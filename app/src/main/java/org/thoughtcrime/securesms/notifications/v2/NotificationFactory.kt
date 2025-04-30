package org.thoughtcrime.securesms.notifications.v2

import android.annotation.TargetApi
import android.app.Notification // Make sure this is imported if needed by builder manipulation
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build // Ensure this import is present
import android.os.TransactionTooLargeException
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.signal.core.util.PendingIntentFlags
import org.signal.core.util.concurrent.SignalExecutors
import org.signal.core.util.logging.Log
import org.thoughtcrime.securesms.MainActivity
import org.thoughtcrime.securesms.R
import org.thoughtcrime.securesms.avatar.fallback.FallbackAvatar
import org.thoughtcrime.securesms.avatar.fallback.FallbackAvatarDrawable
import org.thoughtcrime.securesms.components.emoji.EmojiStrings
import org.thoughtcrime.securesms.conversation.ConversationIntents
import org.thoughtcrime.securesms.conversation.colors.AvatarColor
import org.thoughtcrime.securesms.database.SignalDatabase
import org.thoughtcrime.securesms.database.model.InMemoryMessageRecord
import org.thoughtcrime.securesms.dependencies.AppDependencies
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.notifications.NotificationChannels
import org.thoughtcrime.securesms.notifications.NotificationIds
import org.thoughtcrime.securesms.recipients.Recipient
import org.thoughtcrime.securesms.stories.my.MyStoriesActivity
import org.thoughtcrime.securesms.util.BubbleUtil
import org.thoughtcrime.securesms.util.ConversationUtil
import org.thoughtcrime.securesms.util.ServiceUtil
import org.thoughtcrime.securesms.util.TextSecurePreferences
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Given a notification state consisting of conversations of messages, show appropriate system notifications.
 */
object NotificationFactory {

  val TAG: String = Log.tag(NotificationFactory::class.java)

  private val STILL_DECRYPTING_INDIVIDUAL_THROTTLE: Duration = 5.seconds
  private val GROUP_THROTTLE: Duration = 20.seconds

  fun notify(
    context: Context,
    state: NotificationState,
    visibleThread: ConversationId?,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    notificationConfigurationChanged: Boolean,
    alertOverrides: Set<ConversationId>,
    previousState: NotificationState,
    lastThreadNotification: MutableMap<ConversationId, Long>
  ): Set<ConversationId> {
    if (state.isEmpty) {
      Log.d(TAG, "State is empty, bailing")
      return emptySet()
    }

    val nonVisibleThreadCount: Int = state.conversations.count { it.thread != visibleThread }
    return if (Build.VERSION.SDK_INT < 24) {
      notify19(
        context = context,
        state = state,
        visibleThread = visibleThread,
        targetThread = targetThread,
        defaultBubbleState = defaultBubbleState,
        lastAudibleNotification = lastAudibleNotification,
        alertOverrides = alertOverrides,
        nonVisibleThreadCount = nonVisibleThreadCount,
        lastThreadNotification = lastThreadNotification
      )
    } else {
      notify24(
        context = context,
        state = state,
        visibleThread = visibleThread, // Pass it down
        targetThread = targetThread,
        defaultBubbleState = defaultBubbleState,
        lastAudibleNotification = lastAudibleNotification,
        notificationConfigurationChanged = notificationConfigurationChanged,
        alertOverrides = alertOverrides,
        nonVisibleThreadCount = nonVisibleThreadCount,
        previousState = previousState,
        lastThreadNotification = lastThreadNotification
      )
    }
  }

  private fun notify19(
    context: Context,
    state: NotificationState,
    visibleThread: ConversationId?,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    alertOverrides: Set<ConversationId>,
    nonVisibleThreadCount: Int,
    lastThreadNotification: MutableMap<ConversationId, Long>
  ): Set<ConversationId> {
    val threadsThatNewlyAlerted: MutableSet<ConversationId> = mutableSetOf()

    state.conversations.find { it.thread == visibleThread }?.let { conversation ->
      if (conversation.hasNewNotifications()) {
        Log.internal().i(TAG, "Thread is visible, notifying in thread. notificationId: ${conversation.notificationId}")
        notifyInThread(context, conversation.recipient, lastAudibleNotification)
      }
    }

    if (nonVisibleThreadCount == 1) {
      state.conversations.first { it.thread != visibleThread }.let { conversation ->
        val shouldAlert = shouldAlert(conversation, lastThreadNotification.getOrDefault(conversation.thread, 0), alertOverrides.contains(conversation.thread))
        if (shouldAlert) {
          lastThreadNotification[conversation.thread] = System.currentTimeMillis()
        }

        // Note: visibleThread is implicitly null or different here for API < 24 logic branching
        notifyForConversation(
          context = context,
          conversation = conversation,
          targetThread = targetThread,
          defaultBubbleState = defaultBubbleState,
          shouldAlert = shouldAlert,
          visibleThread = null // Pass null explicitly as this branch implies chat not visible for notification purpose
        )
        if (conversation.hasNewNotifications()) {
          threadsThatNewlyAlerted += conversation.thread
        }
      }
    } else if (nonVisibleThreadCount > 1) {
      val nonVisibleConversations: List<NotificationConversation> = state.getNonVisibleConversation(visibleThread)
      threadsThatNewlyAlerted += nonVisibleConversations.filter { it.hasNewNotifications() }.map { it.thread }
      notifySummary(context = context, state = state.copy(conversations = nonVisibleConversations))
    }

    return threadsThatNewlyAlerted
  }

  @TargetApi(24)
  private fun notify24(
    context: Context,
    state: NotificationState,
    visibleThread: ConversationId?, // <<< Already here
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    lastAudibleNotification: Long,
    notificationConfigurationChanged: Boolean,
    alertOverrides: Set<ConversationId>,
    nonVisibleThreadCount: Int,
    previousState: NotificationState,
    lastThreadNotification: MutableMap<ConversationId, Long>
  ): Set<ConversationId> {
    val threadsThatNewlyAlerted: MutableSet<ConversationId> = mutableSetOf()

    state.conversations.forEach { conversation ->
      // Handle in-thread sound first if the thread is visible
      if (conversation.thread == visibleThread && conversation.hasNewNotifications()) {
        Log.internal().i(TAG, "Thread is visible, notifying in thread. notificationId: ${conversation.notificationId}")
        notifyInThread(context, conversation.recipient, lastAudibleNotification)
      }

      // Determine if we need to create/update the notification drawer entry
      val needsNotificationUpdate = notificationConfigurationChanged ||
        conversation.hasNewNotifications() ||
        alertOverrides.contains(conversation.thread) ||
        !conversation.hasSameContent(previousState.getConversation(conversation.thread))

      if (needsNotificationUpdate) {
        // Check if this conversation *would* alert if it weren't visible
        val wouldAlertGroup = shouldAlert(
          conversation = conversation,
          lastNotificationTimestamp = lastThreadNotification.getOrDefault(conversation.thread, 0),
          alertOverride = alertOverrides.contains(conversation.thread)
        )

        // Only count as "newly alerted" if it would alert AND it's not the one currently visible
        if (conversation.hasNewNotifications() && wouldAlertGroup && conversation.thread != visibleThread) {
          threadsThatNewlyAlerted += conversation.thread
        }

        // Update the last notification time only if it *would* alert and is *not* the visible thread
        if (wouldAlertGroup && conversation.thread != visibleThread) {
          lastThreadNotification[conversation.thread] = System.currentTimeMillis()
        }

        try {
          // Pass visibleThread down to handle silencing
          notifyForConversation(
            context = context,
            conversation = conversation,
            targetThread = targetThread,
            defaultBubbleState = defaultBubbleState,
            shouldAlert = wouldAlertGroup, // Pass the group alert decision
            visibleThread = visibleThread   // <<< Pass the actual visible thread ID
          )
        } catch (e: SecurityException) {
          Log.w(TAG, "Too many pending intents device quirk", e)
        } catch (runtimeException: RuntimeException) { // Catch TransactionTooLargeException and others
          if (runtimeException.cause is TransactionTooLargeException) {
            Log.e(TAG, "Transaction too large when trying to build notification for ${conversation.thread}", runtimeException)
          } else {
            Log.e(TAG, "Unexpected runtime exception building notification for ${conversation.thread}", runtimeException)
            // Decide if you want to rethrow or just log and continue
            // throw runtimeException // Uncomment to crash on unexpected errors
          }
        }
      }
    }

    // Update summary notification if needed
    if (nonVisibleThreadCount > 1 || ServiceUtil.getNotificationManager(context).isDisplayingSummaryNotification()) {
      notifySummary(context = context, state = state.copy(conversations = state.getNonVisibleConversation(visibleThread)))
    }

    return threadsThatNewlyAlerted
  }

  private fun shouldAlert(conversation: NotificationConversation, lastNotificationTimestamp: Long, alertOverride: Boolean): Boolean {
    val throttle: Duration = when {
      conversation.recipient.isGroup && (conversation.mostRecentNotification as? MessageNotification)?.hasSelfMention == false -> GROUP_THROTTLE
      AppDependencies.incomingMessageObserver.decryptionDrained -> STILL_DECRYPTING_INDIVIDUAL_THROTTLE
      else -> 0.seconds
    }
    val canAlertBasedOnTime: Boolean = lastNotificationTimestamp < System.currentTimeMillis() - throttle.inWholeMilliseconds || lastNotificationTimestamp > System.currentTimeMillis()

    // Don't alert if the most recent notification is from self (e.g., reacting to own message)
    // Do alert if override is true or if it's a new notification that passed the time throttle.
    return ((conversation.hasNewNotifications() && canAlertBasedOnTime) || alertOverride) && !conversation.mostRecentNotification.authorRecipient.isSelf
  }

  // <<< Modified function signature
  private fun notifyForConversation(
    context: Context,
    conversation: NotificationConversation,
    targetThread: ConversationId?,
    defaultBubbleState: BubbleUtil.BubbleState,
    shouldAlert: Boolean, // This now mainly affects onlyAlertOnce for non-visible threads
    visibleThread: ConversationId? // <<< Added parameter
  ) {
    if (conversation.notificationItems.isEmpty()) {
      Log.d(TAG, "No items for conversation ${conversation.thread}, skipping notification.")
      return
    }

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    // --- VVV Check if the current notification being built is for the visible chat VVV ---
    val isChatCurrentlyVisible = visibleThread != null && visibleThread == conversation.thread
    // --- ^^^ Check complete ^^^ ---

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.notification_background_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
      setChannelId(conversation.getChannelId())
      setContentTitle(conversation.getContentTitle(context))
      setLargeIcon(conversation.getContactLargeIcon(context).toLargeBitmap(context))
      addPerson(conversation.recipient)

      if (conversation.thread.groupStoryId == null) {
        setShortcutId(ConversationUtil.getShortcutId(conversation.recipient))
        setLocusId(ConversationUtil.getShortcutId(conversation.recipient))
      }

      setContentInfo(conversation.messageCount.toString())
      setNumber(conversation.messageCount)
      setContentText(conversation.getContentText(context))
      setContentIntent(conversation.getPendingIntent(context))
      setDeleteIntent(conversation.getDeleteIntent(context))
      setSortKey(conversation.sortKey.toString())
      setWhen(conversation)
      addReplyActions(conversation)
      addMessages(conversation) // Keep adding messages for context in the drawer
      setBubbleMetadata(conversation, if (targetThread == conversation.thread) defaultBubbleState else BubbleUtil.BubbleState.HIDDEN)
      // Ticker text is less common now but set it just in case
      setTicker(conversation.mostRecentNotification.getStyledPrimaryText(context, true))

      // --- VVV APPLYING YOUR CONDITIONAL SILENCE LOGIC VVV ---
      if (isChatCurrentlyVisible) {
        Log.d(TAG, "Chat ${conversation.thread} is visible. Making notification silent.")
        // Make the notification silent - no sound, no vibration.

        // Option 1: Use setSilent (Preferred for API 26+) - Requires NotificationBuilder support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          // builder.setSilent(true) // Uncomment this IF NotificationBuilder adds this method
        }

        // Option 2: Use setAlarms(null) as a proxy (Requires checking NotificationBuilder.setAlarms implementation)
        // This assumes setAlarms(null) will skip applying default sound/vibration.
        setAlarms(null)

        // Option 3: Manipulate defaults directly (If NotificationBuilder exposes the compat builder, e.g., builder.compatBuilder)
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) { // Example fallback
        //   val currentDefaults = builder.compatBuilder.mNotification.defaults
        //   builder.compatBuilder.setDefaults(currentDefaults and Notification.DEFAULT_SOUND.inv() and Notification.DEFAULT_VIBRATE.inv())
        //   builder.compatBuilder.setSound(null)
        //   builder.compatBuilder.setVibrate(null)
        // }

        // Always lower priority for silent notifications
        setPriority(NotificationCompat.PRIORITY_LOW)

        // Still allow LED lights if configured
        setLights()
        // Alerting only once doesn't make sense if it's silent, but doesn't hurt.
        setOnlyAlertOnce(true)

      } else {
        // This notification is NOT for the currently visible chat. Apply standard alerting.
        Log.d(TAG, "Chat ${conversation.thread} is NOT visible. Applying standard alert.")
        setPriority(TextSecurePreferences.getNotificationPriority(context))
        setLights()
        setAlarms(conversation.recipient) // Apply normal sound/vibration from recipient/defaults
        // Set onlyAlertOnce based on the *group* alert decision passed into this function
        setOnlyAlertOnce(!shouldAlert)
      }
      // --- ^^^ END OF YOUR CONDITIONAL SILENCE LOGIC ^^^ ---
    }

    if (conversation.isOnlyContactJoinedEvent) {
      builder.addTurnOffJoinedNotificationsAction(conversation.getTurnOffJoinedNotificationsIntent(context))
    }

    // Use the conversation's specific ID on API 24+, otherwise use the summary ID
    val notificationId: Int = if (Build.VERSION.SDK_INT < 24) NotificationIds.MESSAGE_SUMMARY else conversation.notificationId

    Log.d(TAG, "Preparing to notify ID: $notificationId for conversation: ${conversation.thread}, Visible: $isChatCurrentlyVisible")
    NotificationManagerCompat.from(context).safelyNotify(conversation.recipient, notificationId, builder.build())
  }

  private fun notifySummary(context: Context, state: NotificationState) {
    if (state.messageCount == 0) {
      Log.d(TAG, "No messages for summary, skipping.")
      return
    }

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    // Determine if any of the underlying messages contributing to the summary were new/should alert
    val shouldAlertSummary = state.notificationItems.any { it.isNewNotification } // Simplified check

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.notification_background_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN) // Alert behaviour driven by children
      setChannelId(NotificationChannels.getInstance().messagesChannel)
      setContentTitle(context.getString(R.string.app_name))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, MainActivity.clearTop(context), PendingIntentFlags.mutable()))
      setGroupSummary(true)
      setSubText(context.buildSummaryString(state.messageCount, state.threadCount))
      setContentInfo(state.messageCount.toString())
      setNumber(state.messageCount)
      setSummaryContentText(state.mostRecentSender)
      setDeleteIntent(state.getDeleteIntent(context))
      setWhen(state.mostRecentNotification)
      addMarkAsReadAction(state)
      addMessages(state) // Add messages for context in the summary's expanded view
      setOnlyAlertOnce(!shouldAlertSummary) // Alert summary only once if any underlying message should have alerted
      setPriority(TextSecurePreferences.getNotificationPriority(context))
      setLights()
      // Set alarms based on the most recent sender for the summary? Or use defaults? Let's use most recent sender for consistency.
      setAlarms(state.mostRecentSender)
      // Ticker for summary might be less useful
      setTicker(state.mostRecentNotification?.getStyledPrimaryText(context, true))
    }

    Log.d(TAG, "Showing summary notification, ID: ${NotificationIds.MESSAGE_SUMMARY}")
    NotificationManagerCompat.from(context).safelyNotify(null, NotificationIds.MESSAGE_SUMMARY, builder.build())
  }

  private fun Context.buildSummaryString(messageCount: Int, threadCount: Int): String {
    val messageString = resources.getQuantityString(R.plurals.MessageNotifier_d_messages, messageCount, messageCount)
    val threadString = resources.getQuantityString(R.plurals.MessageNotifier_d_chats, threadCount, threadCount)
    return getString(R.string.MessageNotifier_s_in_s, messageString, threadString)
  }

  private fun notifyInThread(context: Context, recipient: Recipient, lastAudibleNotification: Long) {
    if (!NotificationChannels.getInstance().areNotificationsEnabled()) {
      Log.d(TAG, "notifyInThread: Notifications disabled globally.")
      return
    }
    if (!SignalStore.settings.isMessageNotificationsInChatSoundsEnabled) {
      Log.d(TAG, "notifyInThread: In-chat sounds disabled.")
      return
    }
    if (ServiceUtil.getAudioManager(context).ringerMode != AudioManager.RINGER_MODE_NORMAL) {
      Log.d(TAG, "notifyInThread: Ringer is not normal mode.")
      return
    }
    if ((System.currentTimeMillis() - lastAudibleNotification) < DefaultMessageNotifier.MIN_AUDIBLE_PERIOD_MILLIS) {
      Log.d(TAG, "notifyInThread: Throttled.")
      return
    }

    val uri: Uri = if (NotificationChannels.supported()) {
      NotificationChannels.getInstance().getMessageRingtone(recipient) ?: NotificationChannels.getInstance().messageRingtone
    } else {
      recipient.messageRingtone ?: SignalStore.settings.messageNotificationSound
    }

    if (uri == Uri.EMPTY || uri.toString().isEmpty()) {
      Log.d(TAG, "notifyInThread: Ringtone URI is empty.")
      return
    }

    try {
      val ringtone = RingtoneManager.getRingtone(context, uri)

      if (ringtone == null) {
        Log.w(TAG, "notifyInThread: Ringtone is null for URI: $uri")
        return
      }

      ringtone.audioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .build()

      Log.d(TAG, "notifyInThread: Playing in-thread sound for ${recipient.id}")
      ringtone.play()
    } catch (e: Exception) {
      Log.w(TAG, "notifyInThread: Failed to play ringtone for URI: $uri", e)
      // Consider falling back to default sound or handling the error
    }
  }

  fun notifyMessageDeliveryFailed(context: Context, recipient: Recipient, thread: ConversationId, visibleThread: ConversationId?, visibleBubbleThread: ConversationId?) {
    if (thread == visibleThread || thread == visibleBubbleThread) {
      Log.d(TAG, "Delivery failed for visible thread ${thread}, playing in-thread sound.")
      notifyInThread(context, recipient, 0) // Use 0 to bypass throttle for failure notification
      return
    }

    val intent: Intent = if (recipient.isDistributionList || thread.groupStoryId != null) {
      Intent(context, MyStoriesActivity::class.java)
    } else {
      ConversationIntents.createBuilderSync(context, recipient.id, thread.threadId)
        .build()
    }.makeUniqueToPreventMerging()

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      // Use a more specific error icon if available
      setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.symbol_error_triangle_fill_32))
      setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_failed))
      setContentText(context.getString(R.string.MessageNotifier_failed_to_deliver_message))
      setTicker(context.getString(R.string.MessageNotifier_error_delivering_message))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, intent, PendingIntentFlags.updateCurrent())) // Use updateCurrent
      setAutoCancel(true)
      setAlarms(recipient) // Allow failure notifications to alert
      setChannelId(NotificationChannels.getInstance().FAILURES)
    }

    val notificationId = NotificationIds.getNotificationIdForMessageDeliveryFailed(thread)
    Log.d(TAG, "Notify delivery failed ID: $notificationId for thread: $thread")
    NotificationManagerCompat.from(context).safelyNotify(recipient, notificationId, builder.build())
  }

  fun notifyStoryDeliveryFailed(context: Context, recipient: Recipient, thread: ConversationId) {
    val intent = Intent(context, MyStoriesActivity::class.java).makeUniqueToPreventMerging()

    val contentTitle = if (SignalStore.settings.messageNotificationsPrivacy.isDisplayContact) {
      if (recipient.isGroup) {
        context.getString(R.string.MessageNotifier_group_story_title, recipient.getDisplayName(context))
      } else {
        recipient.getDisplayName(context)
      }
    } else {
      context.getString(R.string.SingleRecipientNotificationBuilder_signal)
    }

    val largeIcon = if (SignalStore.settings.messageNotificationsPrivacy.isDisplayContact) {
      if (recipient.isMyStory) {
        Recipient.self().getContactDrawable(context)
      } else {
        recipient.getContactDrawable(context)
      }
    } else {
      FallbackAvatarDrawable(context, FallbackAvatar.forTextOrDefault("Unknown", AvatarColor.UNKNOWN)).circleCrop()
    }.toLargeBitmap(context)

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setLargeIcon(largeIcon)
      setContentTitle(contentTitle)
      setContentText(String.format("%s %s", EmojiStrings.FAILED_STORY, context.getString(R.string.MessageNotifier_story_delivery_failed)))
      setTicker(context.getString(R.string.MessageNotifier_story_delivery_failed))
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, intent, PendingIntentFlags.updateCurrent())) // Use updateCurrent
      setAutoCancel(true)
      setAlarms(recipient) // Allow failure notifications to alert
      setChannelId(NotificationChannels.getInstance().FAILURES)
    }

    val notificationId = NotificationIds.getNotificationIdForMessageDeliveryFailed(thread) // Re-use same ID space? Check NotificationIds
    Log.d(TAG, "Notify story delivery failed ID: $notificationId for thread: $thread")
    NotificationManagerCompat.from(context).safelyNotify(recipient, notificationId, builder.build())
  }

  fun notifyProofRequired(context: Context, recipient: Recipient, thread: ConversationId, visibleThread: ConversationId?) {
    if (thread == visibleThread) {
      Log.d(TAG, "Proof required for visible thread ${thread}, playing in-thread sound.")
      notifyInThread(context, recipient, 0) // Use 0 to bypass throttle for this alert
      return
    }

    val intent: Intent = if (recipient.isDistributionList || thread.groupStoryId != null) {
      Intent(context, MyStoriesActivity::class.java)
    } else {
      ConversationIntents.createBuilderSync(context, recipient.id, thread.threadId)
        .build()
    }.makeUniqueToPreventMerging()

    val builder: NotificationBuilder = NotificationBuilder.create(context)

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      // Use a more specific icon if available, e.g., info or warning
      setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.symbol_info_24))
      setContentTitle(context.getString(R.string.MessageNotifier_message_delivery_paused))
      setContentText(context.getString(R.string.MessageNotifier_verify_to_continue_messaging_on_signal))
      setTicker(context.getString(R.string.MessageNotifier_message_delivery_paused)) // More informative ticker
      setContentIntent(NotificationPendingIntentHelper.getActivity(context, 0, intent, PendingIntentFlags.updateCurrent())) // Use updateCurrent
      setOnlyAlertOnce(true) // Probably only need to alert once for this
      setAutoCancel(true)
      setAlarms(recipient) // Allow this notification to alert
      setChannelId(NotificationChannels.getInstance().FAILURES) // Use FAILURES channel or a dedicated one?
    }

    // Use same ID space as failures? Check NotificationIds for uniqueness or dedicated ID.
    val notificationId = NotificationIds.getNotificationIdForMessageDeliveryFailed(thread)
    Log.d(TAG, "Notify proof required ID: $notificationId for thread: $thread")
    NotificationManagerCompat.from(context).safelyNotify(recipient, notificationId, builder.build())
  }

  @JvmStatic
  fun notifyToBubbleConversation(context: Context, recipient: Recipient, threadId: Long) {
    val builder: NotificationBuilder = NotificationBuilder.create(context)

    val conversation = NotificationConversation(
      recipient = recipient,
      thread = ConversationId.forConversation(threadId),
      notificationItems = listOf(
        MessageNotification(
          threadRecipient = recipient,
          record = InMemoryMessageRecord.ForceConversationBubble(recipient, threadId)
        )
      )
    )

    builder.apply {
      setSmallIcon(R.drawable.ic_notification)
      setColor(ContextCompat.getColor(context, R.color.notification_background_ultramarine))
      setCategory(NotificationCompat.CATEGORY_MESSAGE)
      setGroup(DefaultMessageNotifier.NOTIFICATION_GROUP)
      setChannelId(conversation.getChannelId()) // Use appropriate channel
      setContentTitle(conversation.getContentTitle(context))
      setLargeIcon(conversation.getContactLargeIcon(context).toLargeBitmap(context))
      addPerson(conversation.recipient)
      setShortcutId(ConversationUtil.getShortcutId(conversation.recipient))
      setLocusId(ConversationUtil.getShortcutId(conversation.recipient))
      addMessages(conversation) // Add message for context if needed, even if dummy
      // Ensure bubble metadata is correctly configured to *show* the bubble
      setBubbleMetadata(conversation, BubbleUtil.BubbleState.SHOWN)
      // This notification likely shouldn't make noise itself
      setAlarms(null)
      setPriority(NotificationCompat.PRIORITY_DEFAULT) // Or low? Depends if it should pop up visually
      setOnlyAlertOnce(true)
    }

    Log.d(TAG, "Posting Notification for requested bubble, ID: ${conversation.notificationId}")
    NotificationManagerCompat.from(context).safelyNotify(recipient, conversation.notificationId, builder.build())
  }

  private fun NotificationManagerCompat.safelyNotify(threadRecipient: Recipient?, notificationId: Int, notification: Notification) {
    try {
      Log.internal().v(TAG, "Notifying $notificationId :: $notification")
      notify(notificationId, notification)
    } catch (e: SecurityException) {
      // Attempt to handle SecurityException often caused by custom ringtones on some devices
      Log.w(TAG, "Security exception posting notification $notificationId. Clearing ringtone for recipient: ${threadRecipient?.id}", e)
      if (threadRecipient != null && NotificationChannels.supported()) {
        SignalExecutors.BOUNDED.execute {
          try {
            SignalDatabase.recipients.setMessageRingtone(threadRecipient.id, null)
            NotificationChannels.getInstance().updateMessageRingtone(threadRecipient, null)
            // Consider re-posting the notification *without* the custom sound if possible,
            // though modifying the built notification object is tricky. Usually, just clearing
            // the setting for the future is the main action.
          } catch (dbException: Exception) {
            Log.w(TAG, "Failed to clear ringtone setting for recipient ${threadRecipient.id} after SecurityException", dbException)
          }
        }
      } else if (threadRecipient != null) {
        // Handle non-channel case if necessary (though Signal likely requires channels now)
        SignalExecutors.BOUNDED.execute {
          SignalDatabase.recipients.setMessageRingtone(threadRecipient.id, null)
          // Maybe update TextSecurePreferences if that's where older settings were stored?
        }
      }
    } catch (runtimeException: RuntimeException) {
      // Specifically catch TransactionTooLargeException
      if (runtimeException.cause is TransactionTooLargeException || runtimeException is TransactionTooLargeException) {
        Log.e(TAG, "TransactionTooLargeException posting notification $notificationId. Notification might be too complex.", runtimeException)
        // Ideas: Reduce number of messages in history, simplify styles, ensure bitmaps are reasonably sized.
      } else {
        // Log other runtime exceptions but rethrow them to understand crashes
        Log.e(TAG, "Unexpected RuntimeException posting notification $notificationId", runtimeException)
        throw runtimeException
      }
    } catch (error: OutOfMemoryError) {
      // Catch OOM specifically as large bitmaps or complex notifications can cause this
      Log.e(TAG, "OutOfMemoryError posting notification $notificationId. Notification might be too complex or large.", error)
      // Similar remediation ideas as TransactionTooLargeException
    }
  }
}