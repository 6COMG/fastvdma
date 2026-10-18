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
  * Data-integrity stress transfer.
  *
  * Moves `lineCount` contiguous lines of `lineBytes` bytes each (stride 0)
  * through a `DMATop` and checks that the destination memory holds exactly the
  * source byte stream re-chunked at the destination width. The source pattern
  * is pseudo-random (seeded), so lane/byte-order bugs surface.
  *
  * A generous cycle cap turns any deadlock (a transfer that never raises the
  * writer-done interrupt) into a test failure instead of a hang.
  */
class AxiTopStressTest(
    dut: DMATop,
    val reverse: Boolean,
    val lineBytes: Int,
    val lineCount: Int,
    val seed: Long
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
  private val srcBytes = if (reverse) writeBytes else readBytes
  private val dstBytes = if (reverse) readBytes else writeBytes

  require(lineBytes % srcBytes == 0, s"lineBytes ($lineBytes) must be a multiple of srcBytes ($srcBytes)")
  require(lineBytes % dstBytes == 0, s"lineBytes ($lineBytes) must be a multiple of dstBytes ($dstBytes)")

  private val srcLineWords = lineBytes / srcBytes
  private val dstLineWords = lineBytes / dstBytes
  private val totalBytes = lineBytes * lineCount
  private val srcWords = totalBytes / srcBytes
  private val dstWords = totalBytes / dstBytes
  private val bufWords = math.max(totalBytes / readBytes, totalBytes / writeBytes) + 64

  private val readMask = (BigInt(1) << dmaConfig.sourceWidth) - 1
  private val writeMask = (BigInt(1) << dmaConfig.destWidth) - 1

  private val control = new AxiLiteMasterBfm(io.control, peek[Bits], poke[Bits], println)
  private val log: String => Unit = _ => ()
  private val readMem = new Axi4MemoryBfm(io.read, bufWords, peek[Bits], poke[Bits], log)
  private val writeMem = new Axi4MemoryBfm(io.write, bufWords, peek[Bits], poke[Bits], log)

  private val srcMem = if (reverse) writeMem else readMem
  private val dstMem = if (reverse) readMem else writeMem

  private val rng = new scala.util.Random(seed)
  private val srcData: Array[Byte] = Array.fill(totalBytes)((rng.nextInt(256)).toByte)
  private val srcWordData = WordPacking.bytesToWords(srcData, srcBytes)
  for (i <- srcWordData.indices) srcMem.buf(i) = srcWordData(i)

  override def step(n: Int): Unit = {
    for (_ <- 0 until n) {
      readMem.update(0)
      writeMem.update(0)
      control.update(0)
      super.step(1)
    }
  }

  private val label =
    s"src=$srcBytes*8 dst=$dstBytes*8 reverse=$reverse lineBytes=$lineBytes lines=$lineCount"

  // Descriptors (contiguous, stride 0). Reader registers describe the source,
  // writer registers the destination; Direction swaps the physical ports.
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

  // Cap: ~1 byte/cycle lower bound plus a fixed margin; a stalled transfer can
  // never reach the peak rate, so anything above this is a deadlock.
  private val maxCycles = totalBytes + 50000
  var cycles = 0
  while (peek(dut.io.irq.writerDone) != BigInt(1) && cycles < maxCycles) {
    step(1)
    cycles += 1
  }

  assert(peek(dut.io.irq.writerDone) == BigInt(1), s"stress deadlock/timeout after $cycles cycles [$label]")
  assert(peek(dut.io.irq.readerDone) == BigInt(1), s"stress reader not done [$label]")

  val expected = WordPacking.bytesToWords(srcData, dstBytes)
  val dstMask = if (reverse) readMask else writeMask
  for (i <- expected.indices) {
    val got = dstMem.buf(i) & dstMask
    val exp = expected(i) & dstMask
    assert(
      got == exp,
      f"stress mismatch at word $i: got 0x$got%x expected 0x$exp%x [$label]"
    )
  }
}
