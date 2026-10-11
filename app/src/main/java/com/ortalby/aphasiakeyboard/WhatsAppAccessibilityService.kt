package com.ortalby.aphasiakeyboard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

data class ChatMessage(
    val text: String,
    val outgoing: Boolean,
    /** Who wrote it (contact name, or the member name in a group). Null for her own messages. */
    val sender: String?,
)

data class ChatSnapshot(
    val chatTitle: String?,
    val messages: List<ChatMessage>,
) {
    val lastIncoming: ChatMessage? get() = messages.lastOrNull { !it.outgoing }

    /** Plain transcript for the model, oldest first. */
    fun transcript(maxMessages: Int = 10): String =
        messages.takeLast(maxMessages).joinToString("\n") { m ->
            if (m.outgoing) "אני: ${m.text}" else "${m.sender ?: chatTitle ?: "הצד השני"}: ${m.text}"
        }
}

/**
 * Reads the chat that is open on screen in WhatsApp, on demand, when the keyboard asks.
 * Nothing is stored and nothing leaves the phone.
 * It can also press WhatsApp's send button, which the keyboard does only after she approves a message.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reading happens on demand from the keyboard; events are not needed.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    private fun whatsAppRoot(): AccessibilityNodeInfo? {
        for (window in windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() in PACKAGES) return root
        }
        return null
    }

    /** Returns null when no WhatsApp chat window is on screen. */
    fun readOpenChat(): ChatSnapshot? {
        val root = whatsAppRoot() ?: return null
        val pkg = root.packageName.toString()

        val title = root.findAccessibilityNodeInfosByViewId("$pkg:id/conversation_contact_name")
            .firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        val messages = root.findAccessibilityNodeInfosByViewId("$pkg:id/message_text").mapNotNull { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            val row = messageRow(node)
            // Only her own bubbles carry the delivery-status ticks.
            val outgoing = row != null && row.findAccessibilityNodeInfosByViewId("$pkg:id/status").isNotEmpty()
            val sender = if (outgoing) {
                null
            } else {
                row?.findAccessibilityNodeInfosByViewId("$pkg:id/name_in_group_tv")
                    ?.firstOrNull()?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: title
            }
            ChatMessage(text = text, outgoing = outgoing, sender = sender)
        }

        if (title == null && messages.isEmpty()) return null
        return ChatSnapshot(chatTitle = title, messages = messages)
    }

    /** The list item that holds this message bubble. */
    private fun messageRow(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo = node
        var parent: AccessibilityNodeInfo? = current.parent
        while (parent != null) {
            val cls = parent.className?.toString().orEmpty()
            if (cls.endsWith("ListView") || cls.endsWith("RecyclerView")) return current
            current = parent
            parent = current.parent
        }
        return null
    }

    /** Presses WhatsApp's send button. Returns false while the button is not on screen yet. */
    fun clickSend(): Boolean {
        val root = whatsAppRoot() ?: return false
        val pkg = root.packageName.toString()
        val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull() ?: return false
        if (!send.isVisibleToUser) return false
        return send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    companion object {
        val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

        @Volatile
        var instance: WhatsAppAccessibilityService? = null
            private set
    }
}
