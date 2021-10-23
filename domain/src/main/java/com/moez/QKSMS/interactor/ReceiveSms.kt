/*
 * Copyright (C) 2017 Moez Bhatti <moez.bhatti@gmail.com>
 *
 * This file is part of QKSMS.
 *
 * QKSMS is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * QKSMS is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with QKSMS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.moez.QKSMS.interactor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.telephony.SmsMessage
import com.moez.QKSMS.blocking.BlockingClient
import com.moez.QKSMS.extensions.mapNotNull
import com.moez.QKSMS.manager.NotificationManager
import com.moez.QKSMS.manager.ShortcutManager
import com.moez.QKSMS.repository.ConversationRepository
import com.moez.QKSMS.repository.MessageRepository
import com.moez.QKSMS.util.Preferences
import io.reactivex.Flowable
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject

class ReceiveSms @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val blockingClient: BlockingClient,
    private val prefs: Preferences,
    private val messageRepo: MessageRepository,
    private val notificationManager: NotificationManager,
    private val updateBadge: UpdateBadge,
    private val shortcutManager: ShortcutManager,
    private val context: Context

) : Interactor<ReceiveSms.Params>() {

    class Params(val subId: Int, val messages: Array<SmsMessage>)

    override fun buildObservable(params: Params): Flowable<*> {
        return Flowable.just(params)
            .filter { it.messages.isNotEmpty() }
            .mapNotNull {
                // Don't continue if the sender is blocked
                val messages = it.messages
                val address = messages[0].displayOriginatingAddress
                val smscontent = messages[0].displayMessageBody//短信内容
                val action = blockingClient.getAction(address).blockingGet()
                val smsaction = blockingClient.getAction(smscontent).blockingGet()//拦截短信内容
                val shouldDrop = prefs.drop.get()
                //val shouldDrop=false//强制不丢弃短信信息,存入已拦截信息
                Timber.v("block=$action, drop=$shouldDrop")




                // If we should drop the message, don't even save it
                //如果消息被拦截并且shouldDrop为真,则不储存它
                if (action is BlockingClient.Action.Block && shouldDrop) {
                    return@mapNotNull null
                }

                val time = messages[0].timestampMillis
                val body: String = messages
                    .mapNotNull { message -> message.displayMessageBody }
                    .reduce { body, new -> body + new }

                // Add the message to the db
                val message = messageRepo.insertReceivedSms(it.subId, address, body, time)
                var smscontentblock=false
                if (smsaction is BlockingClient.Action.Block)
                {
                    smscontentblock=true
                    messageRepo.markRead(message.threadId)
                    conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), smscontent)
                }
                if (smsaction is BlockingClient.Action.Unblock)
                {
                    conversationRepo.markUnblocked(message.threadId)
                }
                //var addressblock=false
                if(smscontentblock==false)
                {
                    when (action) {
                        is BlockingClient.Action.Block -> {
                            messageRepo.markRead(message.threadId)
                            conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), action.reason)
                            //addressblock=true
                        }
                        is BlockingClient.Action.Unblock -> conversationRepo.markUnblocked(message.threadId)
                        else -> Unit
                    }
                }

                /*
                if(addressblock==false)
                {
                    when (smsaction) {
                        is BlockingClient.Action.Block -> {
                            messageRepo.markRead(message.threadId)
                            conversationRepo.markBlocked(listOf(message.threadId), prefs.blockingManager.get(), smscontent)
                        }
                        is BlockingClient.Action.Unblock -> conversationRepo.markUnblocked(message.threadId)
                        else -> Unit
                    }
                }
                */

                //获取短信验证码
                //	获得6位纯数字
                val SMS_VERIFY_CODE_SIXLENGTH = 6
                val SMS_VERIFY_CODE_FOURLENGTH = 4
                val p = Pattern.compile("(?<![0-9])([0-9]{" + SMS_VERIFY_CODE_SIXLENGTH + "})(?![0-9])")
                val m = p.matcher(smscontent)
                if (m.find()) {//找到6位验证码
                    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipData = ClipData.newPlainText("Label", m.group(0))
                    clipboardManager.setPrimaryClip(clipData)

                }else{//找4位验证码
                    val p1 = Pattern.compile("(?<![0-9])([0-9]{" + SMS_VERIFY_CODE_FOURLENGTH + "})(?![0-9])")
                    val m1 = p1.matcher(smscontent)
                    if (m1.find()) {
                        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clipData = ClipData.newPlainText("Label", m1.group(0))
                        clipboardManager.setPrimaryClip(clipData)
                    }
                }


                message
            }
            .doOnNext { message ->
                conversationRepo.updateConversations(message.threadId) // Update the conversation
            }
            .mapNotNull { message ->
                conversationRepo.getOrCreateConversation(message.threadId) // Map message to conversation
            }
            .filter { conversation -> !conversation.blocked } // Don't notify for blocked conversations
            .doOnNext { conversation ->
                // Unarchive conversation if necessary
                if (conversation.archived) conversationRepo.markUnarchived(conversation.id)
            }
            .map { conversation -> conversation.id } // Map to the id because [delay] will put us on the wrong thread
            .doOnNext { threadId -> notificationManager.update(threadId) } // Update the notification
            .doOnNext { shortcutManager.updateShortcuts() } // Update shortcuts
            .flatMap { updateBadge.buildObservable(Unit) } // Update the badge and widget
    }

}
