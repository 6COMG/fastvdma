/*
Copyright (C) 2026 6COM

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

SPDX-License-Identifier: Apache-2.0
*/

package DMAController.Bfm

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WordPackingSpec extends AnyFlatSpec with Matchers {
  behavior of "WordPacking"

  private val sampleBytes: Array[Byte] =
    Array(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
          0xff.toByte, 0x00, 0x10, 0x20, 0x30, 0x40, 0x50, 0x60)

  it should "pack bytes into little-endian 64-bit words" in {
    val words = WordPacking.bytesToWords(sampleBytes, 8)
    words.length shouldBe 2
    words(0) shouldBe BigInt("0807060504030201", 16)
    words(1) shouldBe BigInt("60504030201000ff", 16)
  }

  it should "pack bytes into little-endian 32-bit words" in {
    val words = WordPacking.bytesToWords(sampleBytes, 4)
    words.length shouldBe 4
    words(0) shouldBe BigInt("04030201", 16)
    words(1) shouldBe BigInt("08070605", 16)
    words(2) shouldBe BigInt("201000ff", 16)
    words(3) shouldBe BigInt("60504030", 16)
  }

  it should "round-trip 64-bit words back to the original bytes" in {
    WordPacking.wordsToBytes(WordPacking.bytesToWords(sampleBytes, 8), 8) shouldBe sampleBytes
  }

  it should "round-trip 32-bit words back to the original bytes" in {
    WordPacking.wordsToBytes(WordPacking.bytesToWords(sampleBytes, 4), 4) shouldBe sampleBytes
  }

  it should "round-trip 512-bit words back to the original bytes" in {
    val bytes512 = Array.tabulate(512)(i => ((i * 13 + 7) & 0xff).toByte)
    val words = WordPacking.bytesToWords(bytes512, 64)
    words.length shouldBe 8
    WordPacking.wordsToBytes(words, 64) shouldBe bytes512
  }

  it should "reject byte buffers that are not a whole number of words" in {
    an[IllegalArgumentException] should be thrownBy {
      WordPacking.bytesToWords(Array[Byte](1, 2, 3), 2)
    }
  }
}
