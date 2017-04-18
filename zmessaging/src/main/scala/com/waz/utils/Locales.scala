/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.utils

import java.lang.Character.UnicodeBlock._
import java.text.{CollationKey, Collator}
import java.util
import java.util.{Comparator, Locale}

import android.annotation.TargetApi
import android.os.Build.VERSION.SDK_INT
import android.os.Build.VERSION_CODES.{JELLY_BEAN_MR2, LOLLIPOP}
import com.waz.ZLog._
import com.waz.service.ZMessaging

import scala.annotation.tailrec
import scala.util.Try

object Locales {
  private implicit val logTag: LogTag = logTagFor(Locales)

  def currentLocale =
    (for {
      context   <- Option(ZMessaging.context)
      resources <- Option(context.getResources)
      config    <- Option(resources.getConfiguration)
      locale    <- Option(config.locale)
    } yield locale) getOrElse Locale.getDefault

  lazy val bcp47 = if (SDK_INT >= LOLLIPOP) AndroidLanguageTags.create else FallbackLanguageTags.create

  // the underlying native collator shows no signs of being thread-safe & locale might change – that's why this is a def instead of a val
  def currentLocaleOrdering: Ordering[String] = new Ordering[String] {
    private[this] val collator = Collator.getInstance(currentLocale)
    final def compare(a: String, b: String): Int = collator.compare(a, b)
  }

  def sortWithCurrentLocale[A](as: IndexedSeq[A], f: A => String): Vector[A] = { // much faster than as.sortBy(f)(currentLocaleOrdering)
    val collator = Collator.getInstance(currentLocale)
    val indexed = Array.tabulate(as.size)(n => (n, collator.getCollationKey(f(as(n)))))
    util.Arrays.sort(indexed, CollationKeyComparator)
    Vector.tabulate(indexed.size)(n => as(indexed(n)._1))
  }

  def preloadTransliterator(): Transliteration = transliteration

  lazy val transliteration = Transliteration.chooseImplementation()

  def transliteration(id: String) = Transliteration.chooseImplementation(id)

  def indexing(locale: Locale = currentLocale): Indexing = Try(Class.forName("libcore.icu.AlphabeticIndex")).flatMap(
    _ => Try(LibcoreIndexing.create(locale))).getOrElse(FallbackIndexing.instance)
}

object CollationKeyComparator extends Comparator[(Int, CollationKey)] {
  override def compare(lhs: (Int, CollationKey), rhs: (Int, CollationKey)): Int = lhs._2 compareTo rhs._2
}

trait LanguageTags {
  def languageTagOf(l: Locale): String
  def localeFor(languageTag: String): Option[Locale]
}

@TargetApi(LOLLIPOP)
object AndroidLanguageTags {
  def create(implicit logTag: LogTag): LanguageTags = new LanguageTags {
    debug("using built-in Android language tag support")
    def languageTagOf(l: Locale): String = l.toLanguageTag
    def localeFor(t: String): Option[Locale] = Try(Locale.forLanguageTag(t)).toOption
  }
}

object FallbackLanguageTags {
  def create(implicit logTag: LogTag): LanguageTags = new LanguageTags {
    debug("using fallback language tag support")
    def languageTagOf(l: Locale): String = {
      val language = if (l.getLanguage.nonEmpty) l.getLanguage else "und"
      val country = l.getCountry
      if (country.isEmpty) language else s"$language-$country"
    }

    val LanguageTag = s"([a-zA-Z]{2,8})(?:-[a-zA-Z]{4})?(?:-([a-zA-Z]{2}|[0-9]{3}))?".r

    def localeFor(t: String): Option[Locale] = t match {
      case LanguageTag(language, region) =>
        Some(
          if (region ne null) new Locale(language, region)
          else new Locale(language))
      case _ =>
        None
    }
  }
}

trait Transliteration {
  def transliterate(s: String): String
}

object Transliteration {
  private implicit val logTag: LogTag = logTagFor(Transliteration)
  private val id = "Any-Latin; Latin-ASCII; Lower; [^\\ 0-9a-z] Remove"
  def chooseImplementation(id: String = id): Transliteration =
    if (Try(Class.forName("libcore.icu.Transliterator")).isSuccess) LibcoreTransliteration.create(id)
    else ICU4JTransliteration.create(id)
}

@TargetApi(JELLY_BEAN_MR2)
object LibcoreTransliteration {
  def create(id: String)(implicit logTag: LogTag): Transliteration = new Transliteration {
    //TODO Dean: Commented out to get tests to run, need to fix this
    //debug("using libcore transliteration")
    //private val delegate = new libcore.icu.Transliterator(id)
    def transliterate(s: String): String = s //delegate.transliterate(s)
  }
}

object ICU4JTransliteration {
  def create(id: String)(implicit logTag: LogTag): Transliteration = new Transliteration {
    debug("using ICU4J transliteration")
    private val delegate = com.ibm.icu.text.Transliterator.getInstance(id)
    def transliterate(s: String): String = delegate.transliterate(s)
  }
}

trait Indexing {
  def labelFor(s: String): String
}

@TargetApi(JELLY_BEAN_MR2)
object LibcoreIndexing {
  def create(locale: Locale)(implicit logTag: LogTag): Indexing = new Indexing {

    private val delegate = new libcore.icu.AlphabeticIndex(locale).getImmutableIndex

    verbose(s"using libcore indexing for locale $locale; buckets: ${delegate.getBucketCount}")

    override def labelFor(s: String): String = {
      val index = delegate.getBucketIndex(s)
      if (index == -1) "#"
      else {
        val label = delegate.getBucketLabel(index)
        if (label.isEmpty) "#" else label
      }
    }
  }
}

object FallbackIndexing {
  lazy val instance: Indexing = new Indexing {
    val blocks = Set(
      BASIC_LATIN,
      LATIN_1_SUPPLEMENT,
      LATIN_EXTENDED_A,
      LATIN_EXTENDED_B,
      LATIN_EXTENDED_ADDITIONAL,
      IPA_EXTENSIONS,
      SPACING_MODIFIER_LETTERS,
      PHONETIC_EXTENSIONS,
      SUPERSCRIPTS_AND_SUBSCRIPTS,
      NUMBER_FORMS,
      ALPHABETIC_PRESENTATION_FORMS,
      HALFWIDTH_AND_FULLWIDTH_FORMS)

    def isProbablyLatin(c: Int): Boolean = {
      val block = Character.UnicodeBlock.of(c)
      if (block eq null) false else blocks(block)
    }

    verbose(s"creating fallback indexing")(logTagFor(FallbackIndexing))

    @tailrec override def labelFor(s: String): String = {
      if (s.isEmpty) "#"
      else {
        val c = s.codePointAt(0)
        if (Character.isDigit(c)) "#"
        else if (c >= 'A' && c <= 'Z') String.valueOf(c.toChar)
        else if (c >= 'a' && c <= 'z') String.valueOf((c - 32).toChar)
        else if (isProbablyLatin(c)) labelFor(Locales.transliteration.transliterate(s.substring(0, Character.charCount(c))))
        else "#"
      }
    }
  }
}
