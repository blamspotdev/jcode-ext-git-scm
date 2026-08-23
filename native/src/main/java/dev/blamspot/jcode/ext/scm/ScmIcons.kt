package dev.blamspot.jcode.ext.scm

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The git vocabulary, drawn rather than borrowed.
 *
 * The panel takes its chrome — refresh, overflow, chevrons, folders, plus and minus — from JCode's
 * own bundle, so those read here exactly as they do everywhere else in the app. But git's own nouns
 * have no entry there, and the near-misses the panel had been reaching for said the wrong thing:
 * fetch was a refresh arrow, push and pull were one arrow shown twice, GitHub was a globe, and
 * drafting a commit message was a speech bubble. An icon-only button that has to be guessed at is a
 * button with no label at all.
 *
 * These are Octicons — the shapes the WebView panel drew, and the ones anyone arriving from GitHub
 * already reads without thinking. They are path data compiled to [ImageVector] on first use rather
 * than drawables or a bundled font: this plugin ships a bare dex with no resource table, so there is
 * no `res/` to hold a drawable and no packaged asset for `Typeface.createFromFile` to open.
 *
 * **Every arc parameter is written out separately.** SVG lets an arc pack its two flags and the
 * following coordinate into one run of digits — `a.75.75 0 100 1.5` — and Android's path parser
 * reads that `100` as the number one hundred, after which the rest of the sub-path is shifted by two
 * arguments and the shape falls apart into disconnected pieces. Spelling the arcs out costs nothing
 * and is the difference between these rendering and not.
 *
 * The fill is opaque black and never seen: `Icon` tints the whole vector, so each glyph wears the
 * colour its caller asks for and follows the theme like everything else in the panel.
 */
internal object ScmIcons {
    val Branch by lazy {
        octicon(
            "Branch",
            "M 11.75 2.5 a .75 .75 0 1 0 0 1.5 a .75 .75 0 0 0 0 -1.5 z m -2.25 .75 a 2.25 2.25 0 1 1 3 2.122 V 6 " +
            "A 2.5 2.5 0 0 1 10 8.5 H 6 a 1 1 0 0 0 -1 1 v 1.128 a 2.251 2.251 0 1 1 -1.5 0 V 5.372 a 2.25 2.25 0 " +
            "1 1 1.5 0 v 1.836 A 2.492 2.492 0 0 1 6 7 h 4 a 1 1 0 0 0 1 -1 v -.628 A 2.25 2.25 0 0 1 9.5 3.25 z " +
            "M 4.25 12 a .75 .75 0 1 0 0 1.5 a .75 .75 0 0 0 0 -1.5 z M 3.5 3.25 a .75 .75 0 1 1 1.5 0 a .75 .75 " +
            "0 0 1 -1.5 0 z",
        )
    }

    /** Two arrows chasing each other: refs coming down, the working tree untouched. */
    val Fetch by lazy {
        octicon(
            "Fetch",
            "M 2.75 8 a 5.25 5.25 0 0 1 9.15 -3.5 H 10 a .75 .75 0 0 0 0 1.5 h 3.25 A .75 .75 0 0 0 14 5.25 V 2 a " +
            ".75 .75 0 0 0 -1.5 0 v 1.28 A 6.75 6.75 0 0 0 1.25 8 a .75 .75 0 0 0 1.5 0 z m 11.5 0 a .75 .75 0 0 " +
            "0 -1.5 0 a 5.25 5.25 0 0 1 -9.15 3.5 H 6 a .75 .75 0 0 0 0 -1.5 H 2.75 a .75 .75 0 0 0 -.75 .75 V 14 " +
            "a .75 .75 0 0 0 1.5 0 v -1.28 A 6.75 6.75 0 0 0 14.25 8 z",
        )
    }

    /** An arrow onto the bar — work arriving here. */
    val Pull by lazy {
        octicon(
            "Pull",
            "M 7.47 10.78 a .75 .75 0 0 0 1.06 0 l 3.75 -3.75 a .75 .75 0 0 0 -1.06 -1.06 L 8.75 8.44 V 1.75 a " +
            ".75 .75 0 0 0 -1.5 0 v 6.69 L 4.78 5.97 a .75 .75 0 0 0 -1.06 1.06 l 3.75 3.75 z M 3.75 13 a .75 .75 " +
            "0 0 0 0 1.5 h 8.5 a .75 .75 0 0 0 0 -1.5 h -8.5 z",
        )
    }

    /** The same arrow off the bar. Direction is the whole difference, so it is drawn, not rotated. */
    val Push by lazy {
        octicon(
            "Push",
            "M 8.53 1.22 a .75 .75 0 0 0 -1.06 0 L 3.72 4.97 a .75 .75 0 1 0 1.06 1.06 L 7.25 3.56 v 6.69 a .75 " +
            ".75 0 0 0 1.5 0 V 3.56 l 2.47 2.47 a .75 .75 0 1 0 1.06 -1.06 L 8.53 1.22 z M 3.75 13 a .75 .75 0 0 " +
            "0 0 1.5 h 8.5 a .75 .75 0 0 0 0 -1.5 h -8.5 z",
        )
    }

    val GitHub by lazy {
        octicon(
            "GitHub",
            "M 8 0 C 3.58 0 0 3.58 0 8 c 0 3.54 2.29 6.53 5.47 7.59 c .4 .07 .55 -.17 .55 -.38 c 0 -.19 -.01 -.82 " +
            "-.01 -1.49 c -2.01 .37 -2.53 -.49 -2.69 -.94 c -.09 -.23 -.48 -.94 -.82 -1.13 c -.28 -.15 -.68 -.52 " +
            "-.01 -.53 c .63 -.01 1.08 .58 1.23 .82 c .72 1.21 1.87 .87 2.33 .66 c .07 -.52 .28 -.87 .51 -1.07 c " +
            "-1.78 -.2 -3.64 -.89 -3.64 -3.95 c 0 -.87 .31 -1.59 .82 -2.15 c -.08 -.2 -.36 -1.02 .08 -2.12 c 0 0 " +
            ".67 -.21 2.2 .82 c .64 -.18 1.32 -.27 2 -.27 c .68 0 1.36 .09 2 .27 c 1.53 -1.04 2.2 -.82 2.2 -.82 c " +
            ".44 1.1 .16 1.92 .08 2.12 c .51 .56 .82 1.27 .82 2.15 c 0 3.07 -1.87 3.75 -3.65 3.95 c .29 .25 .54 " +
            ".73 .54 1.48 c 0 1.07 -.01 1.93 -.01 2.2 c 0 .21 .15 .46 .55 .38 A 8.01 8.01 0 0 0 16 8 c 0 -4.42 " +
            "-3.58 -8 -8 -8 z",
        )
    }

    /** Three stars: what this button writes is a draft, not a record. */
    val Sparkle by lazy {
        octicon(
            "Sparkle",
            "M 8.5 1.4 L 7.4 4.3 L 4.5 5.4 l 2.9 1.1 l 1.1 2.9 l 1.1 -2.9 l 2.9 -1.1 l -2.9 -1.1 L 8.5 1.4 z M " +
            "3.6 8.7 L 3 10.3 l -1.6 .6 l 1.6 .6 l .6 1.6 l .6 -1.6 l 1.6 -.6 l -1.6 -.6 l -.6 -1.6 z m 7.4 1.6 l " +
            "-.5 1.4 l -1.4 .5 l 1.4 .5 l .5 1.4 l .5 -1.4 l 1.4 -.5 l -1.4 -.5 l -.5 -1.4 z",
        )
    }

    val Tree by lazy {
        octicon(
            "Tree",
            "M 2 2.75 h 1.5 v 10.5 H 2 z M 3.5 4 h 3 v 1.5 h -3 z M 6 7.25 h 6 v 1.5 H 6 z M 6 10.5 h 6 V 12 H 6 " +
            "z",
        )
    }

    val List by lazy {
        octicon(
            "List",
            "M 2 3.5 h 12 V 5 H 2 z M 2 7.25 h 12 v 1.5 H 2 z M 2 11 h 12 v 1.5 H 2 z",
        )
    }
}

private fun octicon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f,
    ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black)).build()
