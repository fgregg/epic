package epic.features

/*
 Copyright 2012 David Hall

 Licensed under the Apache License, Version 2.0 (the "License")
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
import epic.framework.Feature
import breeze.linalg._
import collection.mutable.ArrayBuffer
import breeze.text.analyze.{WordShapeGenerator, EnglishWordClassGenerator}
import epic.parser.features.IndicatorFeature
import java.text.NumberFormat
import java.util.Locale
import breeze.util.{Encoder, Index}

final case class IndicatorWSFeature(name: Symbol) extends Feature
final case class SuffixFeature(str: String) extends Feature
final case class PrefixFeature(str: String) extends Feature
final case class ShapeFeature(str: String) extends Feature
final case class SignatureFeature(str: String) extends Feature
final case class SeenWithTagFeature(str: Any) extends Feature
final case class LeftWordFeature(str: Any) extends Feature
final case class RightWordFeature(str: Any) extends Feature



class WordShapeFeaturizer(wordCounts: Counter[String, Double],
                          commonWordThreshold: Int = 20,
                          unknownWordThreshold: Int = 2,
                          prefixOrder: Int = 3,
                          suffixOrder: Int = 4) extends WordFeaturizer[String] with Serializable {
  import WordShapeFeaturizer._

  private val wordIndex = Index(wordCounts.keysIterator)
  private val knownWordFeatures = Encoder.fromIndex(wordIndex).tabulateArray(s => featuresFor(s).toArray)

  def anchor(words: IndexedSeq[String]): WordFeatureAnchoring[String] = new WordFeatureAnchoring[String] {
    val indices = words.map(wordIndex)
    val myFeatures = (0 until words.length).map(i => if (indices(i) < 0) featuresFor(words(i)).toArray else knownWordFeatures(indices(i)))
    def featuresForWord(pos: Int, level: FeaturizationLevel): Array[Feature] = myFeatures(pos)

    def words: IndexedSeq[String] = ???
  }

  //  val signatureGenerator = EnglishWordClassGenerator
  def featuresFor(w: String): IndexedSeq[Feature] = {
    val wc = wordCounts(w)
    val features = ArrayBuffer[Feature]()
    if(wc > commonWordThreshold) {
      ArrayBuffer(IndicatorFeature(w))
    } else {
      if(wc > unknownWordThreshold)
        features += IndicatorFeature(w)
//      features += ShapeFeature(WordShapeGenerator(w))
//      features += SignatureFeature(signatureGenerator.signatureFor(w))

      val wlen = w.length
      val numCaps = (w:Seq[Char]).count{_.isUpper}
      val hasLetter = w.exists(_.isLetter)
      val hasNotLetter = w.exists(!_.isLetter)
      val hasDigit = w.exists(_.isDigit)
      val hasNonDigit = hasLetter || w.exists(!_.isDigit)
      val hasLower = w.exists(_.isLower)
      val hasDash = w.contains('-')
      val numPeriods = w.count('.' ==)
      val hasPeriod = numPeriods > 0

      if(numCaps > 0)  features += hasCapFeature
      if(numCaps > 1)  features += hasManyCapFeature
      val isAllCaps = numCaps > 1 && !hasLower && !hasNotLetter
      if(isAllCaps) features += isAllCapsFeature

      if(w.length == 2 && w(0).isLetter && w(0).isUpper && w(1) == '.') {
        features += isAnInitialFeature
      }

      if(w.length > 1 && w.last == ('.')) {
        features += endsWithPeriodFeature

      }

      var knownLowerCase = false
      var hasTitleCaseVariant = false

      val hasInitialUpper: Boolean = w(0).isUpper || w(0).isTitleCase
      if(hasInitialUpper) {
        features += hasInitCapFeature
        if(wordCounts(w.toLowerCase) > 0) {
          features += hasKnownLCFeature
          knownLowerCase = true
        } else {
          hasTitleCaseVariant = wordCounts(w(0).toTitleCase + w.substring(1).toLowerCase) > 0
          if (isAllCaps && hasTitleCaseVariant) {
            features += hasKnownTitleCaseFeature
          }
        }
      }



      if(!hasLower && hasLetter) features += hasNoLower
      if(hasDash) features += hasDashFeature
      if(hasDigit)  features += hasDigitFeature
      if(!hasLetter)  features += hasNoLetterFeature
      if(hasNotLetter)  features += hasNotLetterFeature

      // acronyms are all upper case with maybe some periods interspersed
      val hasAcronymShape = (
        wlen >= 3 && isAllCaps && wlen < 6
        || wlen >= 2 && hasPeriod && !hasLower && numCaps > 0 && !hasDigit && w.forall(c => c.isLetter || c == '.')
        )
      // make sure it doesn't have a lwoer case or title case variant, common for titles and place names...
      if(hasAcronymShape  && !knownLowerCase && !hasTitleCaseVariant) {
        features += isProbablyAcronymFeature
      }

      // year!
      if(wlen == 4 && !hasNonDigit) {
        val year = try{w.toInt} catch {case e: NumberFormatException => 0}
        if(year >= 1400 && year < 2300) {
          features += isProbablyYearFeature
        }
      }

      if(hasDigit && !hasLetter) {
        try {
          val n = w.replaceAll(",","").toDouble
          if(!hasPeriod)
            features += integerFeature
          else
            features += floatFeature
        } catch {case e: NumberFormatException =>}
      }

      if(wlen > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) {
        features += endsWithSFeature
        if(hasInitialUpper)
          features += hasInitialCapsAndEndsWithSFeature // we mess up NNP and NNPS
      }

      if(wlen >= suffixOrder + 1) {
        for(i <- 1 to suffixOrder) {
          features += (SuffixFeature(w.substring(wlen-i)))
        }
      }

      if(wlen >= prefixOrder + 2) {
        for(i <- 1 to prefixOrder) {
          features += PrefixFeature(w.substring(0,prefixOrder))
        }
      }

      if(wlen > 10) {
        features += longWordFeature
      } else if(wlen < 5) {
        features += shortWordFeature
      }
      features
    }
  }

  def apply(w: String) = featuresFor(w)


}

object WordShapeFeaturizer {

  // features
  val hasNoLower = IndicatorWSFeature('HasNoLower)
  val hasDashFeature = IndicatorWSFeature('HasDash)
  val hasDigitFeature = IndicatorWSFeature('HasDigit)
  val hasNoLetterFeature = IndicatorWSFeature('HasNoLetter)
  val hasNotLetterFeature = IndicatorWSFeature('HasNotLetter)
  val endsWithSFeature = IndicatorWSFeature('EndsWithS)
  val longWordFeature = IndicatorWSFeature('LongWord)
  val shortWordFeature = IndicatorWSFeature('ShortWord)
  val hasKnownLCFeature = IndicatorWSFeature('HasKnownLC)
  val hasKnownTitleCaseFeature = IndicatorWSFeature('HasKnownTC)
  val hasInitCapFeature = IndicatorWSFeature('HasInitCap)
  val hasInitialCapsAndEndsWithSFeature = IndicatorWSFeature('HasInitCapAndEndsWithS)
  val hasCapFeature = IndicatorWSFeature('HasCap)
  val hasManyCapFeature = IndicatorWSFeature('HasManyCap)
  val isAllCapsFeature = IndicatorWSFeature('AllCaps)
  val isProbablyAcronymFeature = IndicatorWSFeature('ProbablyAcronym)
  val isProbablyYearFeature = IndicatorWSFeature('ProbablyYear)
  val startOfSentenceFeature = IndicatorWSFeature('StartOfSentence)
  val integerFeature = IndicatorWSFeature('Integer)
  val floatFeature = IndicatorWSFeature('Float)
  val isAnInitialFeature = IndicatorWSFeature('IsAnInitial)
  val endsWithPeriodFeature = IndicatorWSFeature('EndsWithPeriod)
}
