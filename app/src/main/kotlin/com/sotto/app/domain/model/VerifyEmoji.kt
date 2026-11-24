package com.sotto.app.domain.model

import androidx.annotation.StringRes
import com.sotto.app.R

/**
 * One of the seven emoji "Verify by phone" shows, with the name to read out (ADR-31): [name] in
 * English, [label] in the user's language (what the screen shows).
 */
data class VerifyEmoji(val symbol: String, val name: String, @StringRes val label: Int) {
    companion object {
        /**
         * 64 emoji, so each stands for 6 bits: the list Matrix uses for its emoji verification,
         * chosen to look clearly different on every phone and to be easy to name. The order is
         * part of the protocol: both phones must map the same bits to the same emoji.
         */
        val ALL: List<VerifyEmoji> = listOf(
            VerifyEmoji("🐶", "Dog", R.string.call_emoji_dog),
            VerifyEmoji("🐱", "Cat", R.string.call_emoji_cat),
            VerifyEmoji("🦁", "Lion", R.string.call_emoji_lion),
            VerifyEmoji("🐎", "Horse", R.string.call_emoji_horse),
            VerifyEmoji("🦄", "Unicorn", R.string.call_emoji_unicorn),
            VerifyEmoji("🐷", "Pig", R.string.call_emoji_pig),
            VerifyEmoji("🐘", "Elephant", R.string.call_emoji_elephant),
            VerifyEmoji("🐰", "Rabbit", R.string.call_emoji_rabbit),
            VerifyEmoji("🐼", "Panda", R.string.call_emoji_panda),
            VerifyEmoji("🐓", "Rooster", R.string.call_emoji_rooster),
            VerifyEmoji("🐧", "Penguin", R.string.call_emoji_penguin),
            VerifyEmoji("🐢", "Turtle", R.string.call_emoji_turtle),
            VerifyEmoji("🐟", "Fish", R.string.call_emoji_fish),
            VerifyEmoji("🐙", "Octopus", R.string.call_emoji_octopus),
            VerifyEmoji("🦋", "Butterfly", R.string.call_emoji_butterfly),
            VerifyEmoji("🌷", "Flower", R.string.call_emoji_flower),
            VerifyEmoji("🌳", "Tree", R.string.call_emoji_tree),
            VerifyEmoji("🌵", "Cactus", R.string.call_emoji_cactus),
            VerifyEmoji("🍄", "Mushroom", R.string.call_emoji_mushroom),
            VerifyEmoji("🌏", "Globe", R.string.call_emoji_globe),
            VerifyEmoji("🌙", "Moon", R.string.call_emoji_moon),
            VerifyEmoji("☁️", "Cloud", R.string.call_emoji_cloud),
            VerifyEmoji("🔥", "Fire", R.string.call_emoji_fire),
            VerifyEmoji("🍌", "Banana", R.string.call_emoji_banana),
            VerifyEmoji("🍎", "Apple", R.string.call_emoji_apple),
            VerifyEmoji("🍓", "Strawberry", R.string.call_emoji_strawberry),
            VerifyEmoji("🌽", "Corn", R.string.call_emoji_corn),
            VerifyEmoji("🍕", "Pizza", R.string.call_emoji_pizza),
            VerifyEmoji("🎂", "Cake", R.string.call_emoji_cake),
            VerifyEmoji("❤️", "Heart", R.string.call_emoji_heart),
            VerifyEmoji("😀", "Smiley", R.string.call_emoji_smiley),
            VerifyEmoji("🤖", "Robot", R.string.call_emoji_robot),
            VerifyEmoji("🎩", "Hat", R.string.call_emoji_hat),
            VerifyEmoji("👓", "Glasses", R.string.call_emoji_glasses),
            VerifyEmoji("🔧", "Spanner", R.string.call_emoji_spanner),
            VerifyEmoji("🎅", "Santa", R.string.call_emoji_santa),
            VerifyEmoji("👍", "Thumbs up", R.string.call_emoji_thumbs_up),
            VerifyEmoji("☂️", "Umbrella", R.string.call_emoji_umbrella),
            VerifyEmoji("⌛", "Hourglass", R.string.call_emoji_hourglass),
            VerifyEmoji("⏰", "Clock", R.string.call_emoji_clock),
            VerifyEmoji("🎁", "Gift", R.string.call_emoji_gift),
            VerifyEmoji("💡", "Light bulb", R.string.call_emoji_light_bulb),
            VerifyEmoji("📕", "Book", R.string.call_emoji_book),
            VerifyEmoji("✏️", "Pencil", R.string.call_emoji_pencil),
            VerifyEmoji("📎", "Paperclip", R.string.call_emoji_paperclip),
            VerifyEmoji("✂️", "Scissors", R.string.call_emoji_scissors),
            VerifyEmoji("🔒", "Lock", R.string.call_emoji_lock),
            VerifyEmoji("🔑", "Key", R.string.call_emoji_key),
            VerifyEmoji("🔨", "Hammer", R.string.call_emoji_hammer),
            VerifyEmoji("☎️", "Telephone", R.string.call_emoji_telephone),
            VerifyEmoji("🏁", "Flag", R.string.call_emoji_flag),
            VerifyEmoji("🚂", "Train", R.string.call_emoji_train),
            VerifyEmoji("🚲", "Bicycle", R.string.call_emoji_bicycle),
            VerifyEmoji("✈️", "Aeroplane", R.string.call_emoji_aeroplane),
            VerifyEmoji("🚀", "Rocket", R.string.call_emoji_rocket),
            VerifyEmoji("🏆", "Trophy", R.string.call_emoji_trophy),
            VerifyEmoji("⚽", "Ball", R.string.call_emoji_ball),
            VerifyEmoji("🎸", "Guitar", R.string.call_emoji_guitar),
            VerifyEmoji("🎺", "Trumpet", R.string.call_emoji_trumpet),
            VerifyEmoji("🔔", "Bell", R.string.call_emoji_bell),
            VerifyEmoji("⚓", "Anchor", R.string.call_emoji_anchor),
            VerifyEmoji("🎧", "Headphones", R.string.call_emoji_headphones),
            VerifyEmoji("📁", "Folder", R.string.call_emoji_folder),
            VerifyEmoji("📌", "Pin", R.string.call_emoji_pin),
        )

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
