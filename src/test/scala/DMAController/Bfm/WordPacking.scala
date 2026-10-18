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

import java.nio._

/**
  * Converts between a flat byte stream (as stored in image files) and words
  * of `dataBytes` bytes, using little-endian (AXI) byte order: byte 0 of the
  * stream is the least-significant byte of word 0, matching the AXI data-bus
  * byte-lane convention (lowest address on the low-order lanes). This must
  * stay independent of the configured data width so a 512-bit DMA bus is
  * handled exactly like a 32-bit one.
  *
  * Words are represented as `BigInt` so widths above 64 bits are supported.
  */
object WordPacking {

  /** Split a byte buffer into words of `dataBytes` bytes (little-endian). */
  def bytesToWords(buffer: Array[Byte], dataBytes: Int): Array[BigInt] = {
    require(dataBytes > 0, "dataBytes must be positive")
    require(buffer.length % dataBytes == 0,
      s"Buffer size (${buffer.length}) is not a multiple of the data width ($dataBytes bytes)")
    val words = new Array[BigInt](buffer.length / dataBytes)
    for (i <- words.indices) {
      var v = BigInt(0)
      for (k <- 0 until dataBytes) {
        v = v | (BigInt(buffer(i * dataBytes + k) & 0xff) << (8 * k))
      }
      words(i) = v
    }
    words
  }

  /** Flatten words of `dataBytes` bytes into a little-endian byte array. */
  def wordsToBytes(words: Array[BigInt], dataBytes: Int): Array[Byte] = {
    require(dataBytes > 0, "dataBytes must be positive")
    val out = new Array[Byte](dataBytes * words.length)
    for (i <- words.indices) {
      val v = words(i)
      for (k <- 0 until dataBytes) {
        out(i * dataBytes + k) = ((v >> (8 * k)) & 0xff).toByte
      }
    }
    out
  }

  /** Read `filename` and return its contents as words of `dataBytes` bytes. */
  def loadWords(filename: String, dataBytes: Int): Array[BigInt] = {
    bytesToWords(file.Files.readAllBytes(file.Paths.get(filename)), dataBytes)
  }

  /** Write `words` to `filename` as little-endian bytes of `dataBytes` bytes each. */
  def saveWords(filename: String, words: Array[BigInt], dataBytes: Int): Unit = {
    file.Files.write(file.Paths.get(filename), wordsToBytes(words, dataBytes))
  }
}
