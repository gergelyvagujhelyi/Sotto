package com.sotto.app.domain.model

/** One of the seven emoji "Verify by phone" shows, with the name to read out (ADR-31). */
data class VerifyEmoji(val symbol: String, val name: String) {
    companion object {
        /**
         * 64 emoji, so each stands for 6 bits: the list Matrix uses for its emoji verification,
         * chosen to look clearly different on every phone and to be easy to name. The order is
         * part of the protocol: both phones must map the same bits to the same emoji.
         */
        val ALL: List<VerifyEmoji> = listOf(
            "🐶" to "Dog", "🐱" to "Cat", "🦁" to "Lion", "🐎" to "Horse",
            "🦄" to "Unicorn", "🐷" to "Pig", "🐘" to "Elephant", "🐰" to "Rabbit",
            "🐼" to "Panda", "🐓" to "Rooster", "🐧" to "Penguin", "🐢" to "Turtle",
            "🐟" to "Fish", "🐙" to "Octopus", "🦋" to "Butterfly", "🌷" to "Flower",
            "🌳" to "Tree", "🌵" to "Cactus", "🍄" to "Mushroom", "🌏" to "Globe",
            "🌙" to "Moon", "☁️" to "Cloud", "🔥" to "Fire", "🍌" to "Banana",
            "🍎" to "Apple", "🍓" to "Strawberry", "🌽" to "Corn", "🍕" to "Pizza",
            "🎂" to "Cake", "❤️" to "Heart", "😀" to "Smiley", "🤖" to "Robot",
            "🎩" to "Hat", "👓" to "Glasses", "🔧" to "Spanner", "🎅" to "Santa",
            "👍" to "Thumbs up", "☂️" to "Umbrella", "⌛" to "Hourglass", "⏰" to "Clock",
            "🎁" to "Gift", "💡" to "Light bulb", "📕" to "Book", "✏️" to "Pencil",
            "📎" to "Paperclip", "✂️" to "Scissors", "🔒" to "Lock", "🔑" to "Key",
            "🔨" to "Hammer", "☎️" to "Telephone", "🏁" to "Flag", "🚂" to "Train",
            "🚲" to "Bicycle", "✈️" to "Aeroplane", "🚀" to "Rocket", "🏆" to "Trophy",
            "⚽" to "Ball", "🎸" to "Guitar", "🎺" to "Trumpet", "🔔" to "Bell",
            "⚓" to "Anchor", "🎧" to "Headphones", "📁" to "Folder", "📌" to "Pin",
        ).map { (symbol, name) -> VerifyEmoji(symbol, name) }

        /** How many emoji are shown: 7 × 6 = 42 bits, as Matrix does. */
        const val COUNT = 7

        /** Seven emoji from the first 42 bits of [digest], 6 bits each (1 in 4.4 trillion). */
        fun fromDigest(digest: ByteArray): List<VerifyEmoji> {
            var bits = 0L
            for (i in 0 until 6) bits = (bits shl 8) or (digest[i].toLong() and 0xFF)
            // 48 bits read; the top 42 are used.
            return (0 until COUNT).map { i -> ALL[((bits ushr (42 - 6 * i)) and 0x3F).toInt()] }
        }
    }
}
