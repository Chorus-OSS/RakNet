package org.chorus_oss.raknet.utils

infix fun UByte.hasFlag(flag: UByte): Boolean = this and flag == flag