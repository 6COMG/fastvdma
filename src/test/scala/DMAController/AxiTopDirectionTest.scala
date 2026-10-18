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

import DMAController.Bfm.{AxiLiteMasterBfm, Axi4MemoryBfm, WordPacking}
import DMAController.Bus.{AXI4, AXI4Lite}
import DMAController.Worker.{InterruptBundle, SyncBundle}
import chisel3.{Bits, Bundle}
import chiseltest.iotesters.PeekPokeTester
import DMAController.DMAConfig._

/**
  * Exercises a bidirectional `DMATop` with fixed physical ports
  * (`sourceWidth` on `io.read`, `destWidth` on `io.write`).
  *
  * `reverse = false` moves data from `io.read` to `io.write`, `reverse = true`
  * moves it the other way using the same ports (the `Direction` register picks
  * which port is the source). Both cases move an identical contiguous byte
  * stream, so the destination is the source bytes packed at its own width.
  */
class AxiTopDirectionTest(
    dut: DMATop,
    val reverse: Boolean,
    lineBytesOverride: Int = 0,
    lineCountOverride: Int = 0
)(implicit val dmaConfig: DMAConfig)
    extends PeekPokeTester(dut) {

  private val io = dut.io.asInstanceOf[Bundle {
    val control: AXI4Lite
    val read: AXI4
    val write: AXI4
    val irq: InterruptBundle
    val sync: SyncBundle
  }]

  private val readBytes = dmaConfig.sourceWidth / 8
  private val writeBytes = dmaConfig.destWidth / 8

  // Source and destination widths depend on the selected direction.
  private val srcBytes = if (reverse) writeBytes else readBytes
  private val dstBytes = if (reverse) readBytes else writeBytes

  private def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
  private def lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

  private val lineBytes = if (lineBytesOverride > 0) lineBytesOverride else 2 * lcm(srcBytes, dstBytes)
  private val lineCount = if (lineCountOverride > 0) lineCountOverride else 16
  private val totalBytes = lineBytes * lineCount
  private val srcLineWords = lineBytes / srcBytes
  private val dstLineWords = lineBytes / dstBytes

  private val bufWords = math.max(totalBytes / readBytes, totalBytes / writeBytes) + 64

  private val readMask = (BigInt(1) << dmaConfig.sourceWidth) - 1
  private val writeMask = (BigInt(1) << dmaConfig.destWidth) - 1

  private val control = new AxiLiteMasterBfm(io.control, peek[Bits], poke[Bits], println)
  private val readMem = new Axi4MemoryBfm(io.read, bufWords, peek[Bits], poke[Bits], println)
  private val writeMem = new Axi4MemoryBfm(io.write, bufWords, peek[Bits], poke[Bits], println)

  private val srcMem = if (reverse) writeMem else readMem
  private val dstMem = if (reverse) readMem else writeMem

  // Deterministic source byte stream.
  private val srcData: Array[Byte] = Array.tabulate(totalBytes)(i => ((i * 7 + 3) & 0xff).toByte)
  private val srcWords = WordPacking.bytesToWords(srcData, srcBytes)
  for (i <- srcWords.indices) srcMem.buf(i) = srcWords(i)

  override def step(n: Int): Unit = {
    for (_ <- 0 until n) {
      readMem.update(0)
      writeMem.update(0)
      control.update(0)
      super.step(1)
    }
  }

  // Source descriptor goes to the "Reader" registers, destination to "Writer",
  // regardless of which physical port is active: the Direction register swaps
  // the roles.
  control.writePush(Register.ReaderStartAddr, 0)
  control.writePush(Register.ReaderLineLen, srcLineWords)
  control.writePush(Register.ReaderLineCnt, lineCount)
  control.writePush(Register.ReaderStride, 0)

  control.writePush(Register.WriterStartAddr, 0)
  control.writePush(Register.WriterLineLen, dstLineWords)
  control.writePush(Register.WriterLineCnt, lineCount)
  control.writePush(Register.WriterStride, 0)

  control.writePush(Register.Direction, if (reverse) 1 else 0)

  step(200)

  control.writePush(Register.InterruptMask, 3)
  control.writePush(Register.Ctrl, 0xf)

  var cycles = 0
  while (peek(dut.io.irq.writerDone) != BigInt(1) && cycles < 200000) {
    step(1)
    cycles += 1
  }

  expect(dut.io.irq.writerDone, 1)
  expect(dut.io.irq.readerDone, 1)

  val expected = WordPacking.bytesToWords(srcData, dstBytes)
  val dstMask = if (reverse) readMask else writeMask
  for (i <- expected.indices) {
    val got = dstMem.buf(i) & dstMask
    val exp = expected(i) & dstMask
    assert(
      got == exp,
      s"direction=${if (reverse) "reverse" else "forward"} mismatch at word $i: " +
        s"got 0x${got.toString(16)}, expected 0x${exp.toString(16)}"
    )
  }
}
