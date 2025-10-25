package org.chorus_oss.raknet.types

enum class RakReliability {
    Unreliable,
    UnreliableSequenced,
    Reliable,
    ReliableOrdered,
    ReliableSequenced,
    UnreliableWithAckReceipt,
    ReliableWithAckReceipt,
    ReliableOrderedWithAckReceipt;

    val isReliable: Boolean
        get() = when (this) {
            Reliable,
            ReliableOrdered,
            ReliableSequenced,
            ReliableWithAckReceipt,
            ReliableOrderedWithAckReceipt -> true

            else -> false
        }

    val isSequenced: Boolean
        get() = when (this) {
            ReliableSequenced,
            UnreliableSequenced -> true

            else -> false
        }

    val isOrdered: Boolean
        get() = when (this) {
            ReliableOrdered,
            ReliableOrderedWithAckReceipt -> true

            else -> false
        }
}