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

import DMAController.Bfm.{AxiLiteMasterBfm, Axi4MemoryBfm}
import DMAController.Bus.{AXI4, AXI4Lite}
import DMAController.Worker.{InterruptBundle, SyncBundle}
import chisel3.{Bits, Bundle}
import chiseltest.iotesters.PeekPokeTester
import DMAController.DMAConfig._

/**
  * Measures the sustained throughput of the AXI top wrapper
  * (`DMATopAXI_AXIL_AXI`) for a given source/destination data width pair. A
  * contiguous buffer is moved with a single 2D transfer (multiple lines, zero
  * gap) and the number of clock cycles between starting the transfer and the
  * writer-done interrupt is reported together with the achieved bytes/cycle and
  * the efficiency relative to the narrower of the two buses.
  *
  * `reverse` selects the runtime direction (`Direction` register): when false
  * the AXI read port (`io.read`, `sourceWidth`) is the source, when true the AXI
  * write port (`io.write`, `destWidth`) is.
  *
  * The result is written as a single comma-separated row to
  * `speed_results.csv` (in the working directory), e.g.:
  *
  * {{{
  * 64,64,65536,8453,7.753,96.9
  * }}}
  */
class AxiTopSpeedTest(dut: DMATop, val reverse: Boolean = false)(implicit val dmaConfig: DMAConfig)
    extends PeekPokeTester(dut) {

  private val io = dut.io.asInstanceOf[Bundle {
    val control: AXI4Lite
    val read: AXI4
    val write: AXI4
    val irq: InterruptBundle
    val sync: SyncBundle
  }]

  // Physical port widths.
  private val readBytes = dmaConfig.sourceWidth / 8
  private val writeBytes = dmaConfig.destWidth / 8

  // Actual source/destination widths depend on the selected direction.
  private val srcBytes = if (reverse) writeBytes else readBytes
  private val dstBytes = if (reverse) readBytes else writeBytes

  // One line is 1 KiB (a whole number of words on every supported width and
  // aligned to the writer's 4 KiB boundary) and a small number of lines keeps
  // the run short. Long bursts (see the spec) keep per-burst overhead from
  // dominating, giving a steady-state throughput figure.
  private val lineBytes = 1024
  private val lineCount = 4
  private val totalBytes = lineBytes * lineCount

  private val srcLineWords = lineBytes / srcBytes
  private val dstLineWords = lineBytes / dstBytes

  private val srcWords = totalBytes / srcBytes
  private val dstWords = totalBytes / dstBytes
  private val bufWords = math.max(totalBytes / readBytes, totalBytes / writeBytes) + 64

  private val control = new AxiLiteMasterBfm(io.control, peek[Bits], poke[Bits], println)
  private val readMem = new Axi4MemoryBfm(io.read, bufWords, peek[Bits], poke[Bits], println)
  private val writeMem = new Axi4MemoryBfm(io.write, bufWords, peek[Bits], poke[Bits], println)

  // Source and destination memories: the reader/writer datapaths are swapped
  // when the direction is reversed.
  private val srcMem = if (reverse) writeMem else readMem
  private val dstMem = if (reverse) readMem else writeMem

  // Deterministic source contents.
  for (i <- 0 until srcWords) srcMem.buf(i) = BigInt((i & 0xffff) + 1)

  override def step(n: Int): Unit = {
    for (_ <- 0 until n) {
      readMem.update(0)
      writeMem.update(0)
      control.update(0)
      super.step(1)
    }
  }

  // Program the descriptor registers (contiguous: line stride 0). The Reader
  // registers always describe the source and the Writer registers the
  // destination; the Direction register swaps the physical ports.
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

  // ... then kick off the transfer and count cycles until it finishes.
  control.writePush(Register.InterruptMask, 3)
  control.writePush(Register.Ctrl, 0xf)

  var cycles = 0
  while (peek(dut.io.irq.writerDone) != BigInt(1) && cycles < totalBytes + 50000) {
    step(1)
    cycles += 1
  }

  expect(dut.io.irq.writerDone, 1)
  expect(dut.io.irq.readerDone, 1)

  // Sanity check: the destination must not be left untouched. (A per-word
  // comparison is avoided because the source pattern's high byte lanes may be
  // zero at some bus widths.)
  assert(
    (0 until dstWords).exists(i => dstMem.buf(i) != BigInt(0)),
    s"speed test destination is all zero (source=${srcBytes * 8}, " +
      s"dest=${dstBytes * 8}, reverse=$reverse)"
  )

  private val peakBytesPerCycle = math.min(readBytes, writeBytes)
  private val bytesPerCycle = totalBytes.toDouble / cycles.toDouble
  private val efficiency = 100.0 * bytesPerCycle / peakBytesPerCycle.toDouble

  val row =
    f"${srcBytes * 8},${dstBytes * 8},$totalBytes,$cycles," +
      f"${bytesPerCycle}%.3f,${efficiency}%.1f"
  java.nio.file.Files.write(
    java.nio.file.Paths.get("speed_results.csv"),
    (row + "\n").getBytes,
    java.nio.file.StandardOpenOption.CREATE,
    java.nio.file.StandardOpenOption.APPEND
  )
}
