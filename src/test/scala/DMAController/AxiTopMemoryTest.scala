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
  * Exercises the AXI top wrapper (`DMATopAXI_AXIL_AXI`): it configures a
  * strided 2D memory-to-memory transfer through the AXI4-Lite CSR and drives
  * the AXI4 read/write ports with memory bus-functional models, then checks
  * that the destination memory contains exactly the source data.
  *
  * The reader and writer data widths may differ (e.g. 64-bit read / 512-bit
  * write). A byte stream of `lineBytes` per line is moved; each side's line
  * length/stride registers are expressed in that side's bus-width units, and
  * the expected destination words are the little-endian (AXI) packing of the
  * same byte stream at the writer width.
  */
class AxiTopMemoryTest(dut: DMATop)(implicit val dmaConfig: DMAConfig) extends PeekPokeTester(dut) {

  private val io = dut.io.asInstanceOf[Bundle {
    val control: AXI4Lite
    val read: AXI4
    val write: AXI4
    val irq: InterruptBundle
    val sync: SyncBundle
  }]

  private val readBytes = dmaConfig.sourceWidth / 8
  private val writeBytes = dmaConfig.destWidth / 8

  private def gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
  private def lcm(a: Int, b: Int): Int = a / gcd(a, b) * b

  // Byte geometry (line length is a whole number of words on both sides).
  private val lineBytes = 2 * lcm(readBytes, writeBytes)
  private val lineCount = 4
  private val readLineWords = lineBytes / readBytes
  private val writeLineWords = lineBytes / writeBytes
  private val readerStride = 0
  private val writerStride = writeLineWords
  private val readerStartAddr = 0
  private val writerStartAddr = 1024
  private val writerStartWord = writerStartAddr / writeBytes

  private val writeWordMask = (BigInt(1) << dmaConfig.destWidth) - 1

  private val bufWords = 2048
  private val control = new AxiLiteMasterBfm(io.control, peek[Bits], poke[Bits], println)
  private val reader = new Axi4MemoryBfm(io.read, bufWords, peek[Bits], poke[Bits], println)
  private val writer = new Axi4MemoryBfm(io.write, bufWords, peek[Bits], poke[Bits], println)

  // Deterministic source byte stream (all byte lanes exercised).
  private val srcBytes: Array[Byte] = Array.tabulate(lineBytes * lineCount)(i => ((i * 7 + 3) & 0xff).toByte)

  override def step(n: Int): Unit = {
    for (_ <- 0 until n) {
      reader.update(0)
      writer.update(0)
      control.update(0)
      super.step(1)
    }
  }

  // Preload the source (reader) memory.
  private val srcWords = WordPacking.bytesToWords(srcBytes, readBytes)
  for (i <- srcWords.indices) reader.buf(i) = srcWords(i)

  // Program the descriptor registers through the AXI4-Lite CSR.
  control.writePush(Register.ReaderStartAddr, readerStartAddr)
  control.writePush(Register.ReaderLineLen, readLineWords)
  control.writePush(Register.ReaderLineCnt, lineCount)
  control.writePush(Register.ReaderStride, readerStride)
  control.writePush(Register.WriterStartAddr, writerStartAddr)
  control.writePush(Register.WriterLineLen, writeLineWords)
  control.writePush(Register.WriterLineCnt, lineCount)
  control.writePush(Register.WriterStride, writerStride)

  step(100)

  control.writePush(Register.InterruptMask, 3)
  control.writePush(Register.Ctrl, 0xf)

  var cycles = 0
  while (peek(dut.io.irq.writerDone) != BigInt(1) && cycles < 200000) {
    step(1)
    cycles += 1
  }

  expect(dut.io.irq.writerDone, 1)
  expect(dut.io.irq.readerDone, 1)

  // The destination must hold the same byte stream, packed at writer width.
  for (line <- 0 until lineCount) {
    val lineBytesSlice = srcBytes.slice(line * lineBytes, (line + 1) * lineBytes)
    val expected = WordPacking.bytesToWords(lineBytesSlice, writeBytes)
    for (j <- expected.indices) {
      val dst = writerStartWord + line * (writeLineWords + writerStride) + j
      val got = writer.buf(dst) & writeWordMask
      val exp = expected(j) & writeWordMask
      assert(
        got == exp,
        s"AXI top data mismatch (read=${dmaConfig.sourceWidth}, write=${dmaConfig.destWidth}) " +
          s"at line=$line word=$j: got 0x${got.toString(16)}, expected 0x${exp.toString(16)}"
      )
    }
  }
}
