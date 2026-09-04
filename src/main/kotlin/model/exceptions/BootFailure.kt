package model.exceptions

/**
 * A boot-time failure with a message meant for the person at the keyboard: what went wrong and
 * what to do about it. The boot screen shows the message and stays on the menu.
 */
open class BootFailure(message: String) : Exception(message)
