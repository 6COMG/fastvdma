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

package DMAController

import chiseltest._
import chiseltest.iotesters.PeekPokeTester
import org.scalatest.flatspec.AnyFlatSpec
import DMAController.DMAConfig._
import DMAUtils.WidthConverter

/**
  * Directed byte-order tests for the width converter.
  *
  * These pin the AXI little-endian lane mapping independently of
  * `WordPacking`/the BFMs: the first input word (lowest address) must land in
  * the least-significant bits of the wider word -- the DLAC `32<->512` island
  * crossing regression. Round-trip integration tests cannot catch this because
  * their oracle shares the converter's packing assumption.
  */
class WidthConverterSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "WidthConverter"

  implicit val dmaConfig: DMAConfig =
    new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 512)

  /** Pack chunks little-endian: chunk 0 occupies the least-significant bits. */
  private def lanePacked(chunks: Seq[BigInt], chunkWidth: Int): BigInt =
    chunks.zipWithIndex.foldLeft(BigInt(0)) {
      case (acc, (chunk, i)) => acc | (chunk << (chunkWidth * i))
    }

  private val narrowWidth = 32
  private val ratio = 16
  private val chunks: Seq[BigInt] = (0 until ratio).map(n => BigInt(n + 1))
  private val wideWord: BigInt = lanePacked(chunks, narrowWidth)

  it should "place the first input word in the least-significant bits when widening" in {
    test(new WidthConverter(narrowWidth, narrowWidth * ratio))
      .runPeekPoke(new WidthConverterWidenTest(_, chunks, wideWord))
  }

  it should "emit the least-significant chunk first when narrowing" in {
    test(new WidthConverter(narrowWidth * ratio, narrowWidth))
      .runPeekPoke(new WidthConverterNarrowTest(_, chunks, wideWord))
  }
}

/** Drives `ratio` input words back-to-back and checks the assembled wide word. */
class WidthConverterWidenTest(dut: WidthConverter, chunks: Seq[BigInt], expected: BigInt)
    extends PeekPokeTester(dut) {
  poke(dut.io.out.ready, 1)
  poke(dut.io.in.valid, 0)
  step(1)

  for (n <- chunks.indices) {
    poke(dut.io.in.valid, 1)
    poke(dut.io.in.bits, chunks(n))
    step(1)
  }
  poke(dut.io.in.valid, 0)

  expect(dut.io.out.valid, 1)
  expect(dut.io.out.bits, expected)
}

/** Feeds one wide word and checks the chunks come out least-significant first. */
class WidthConverterNarrowTest(dut: WidthConverter, chunks: Seq[BigInt], wideWord: BigInt)
    extends PeekPokeTester(dut) {
  poke(dut.io.out.ready, 1)
  poke(dut.io.in.valid, 1)
  poke(dut.io.in.bits, wideWord)
  step(1)
  poke(dut.io.in.valid, 0)

  for (n <- chunks.indices) {
    expect(dut.io.out.valid, 1)
    expect(dut.io.out.bits, chunks(n))
    step(1)
  }
}
