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
  * Exercises the default depth-1 pending start: transfer 2 is programmed and
  * armed **from the same reset without waiting for transfer 1 to finish**. The
  * frontend must snapshot transfer 2's descriptor and launch it after transfer 1,
  * so both destination addresses end up holding their own tag. Before the pending
  * start this pattern silently dropped transfer 2's start and re-used transfer
  * 1's address.
  */
class AxiTopMultiArmTest(dut: DMATop)(implicit val dmaConfig: DMAConfig) extends PeekPokeTester(dut) {

  private val io = dut.io.asInstanceOf[Bundle {
    val control: AXI4Lite
    val read: AXI4
    val write: AXI4
    val irq: InterruptBundle
    val sync: SyncBundle
  }]

  private val readBytes = dmaConfig.sourceWidth / 8
  private val writeBytes = dmaConfig.destWidth / 8

  // Each transfer is 1 KiB (256 reader words = one max AXI read burst): long
  // enough that programming + arming transfer 2 lands while transfer 1 is still
  // in flight, and within the 256-beat AXI burst limit.
  private val lineBytes = 1024
  private val readLineWords = lineBytes / readBytes
  private val writeLineWords = lineBytes / writeBytes

  private val writeMask = (BigInt(1) << dmaConfig.destWidth) - 1
  private val bufWords = 8192

  private val control = new AxiLiteMasterBfm(io.control, peek[Bits], poke[Bits], println)
  private val reader = new Axi4MemoryBfm(io.read, bufWords, peek[Bits], poke[Bits], println)
  private val writer = new Axi4MemoryBfm(io.write, bufWords, peek[Bits], poke[Bits], println)

  override def step(n: Int): Unit = {
    for (_ <- 0 until n) {
      reader.update(0)
      writer.update(0)
      control.update(0)
      super.step(1)
    }
  }

  private def tagBytes(base: Int): Array[Byte] =
    Array.tabulate(lineBytes)(i => ((base + i * 7 + 3) & 0xff).toByte)

  private val tagA = tagBytes(0x10)
  private val tagB = tagBytes(0x80)

  // Distinct source regions: A at reader address 0, B at reader address 4096.
  private val wordsA = WordPacking.bytesToWords(tagA, readBytes)
  private val wordsB = WordPacking.bytesToWords(tagB, readBytes)
  for (i <- wordsA.indices) reader.buf(i) = wordsA(i)
  for (i <- wordsB.indices) reader.buf(4096 / readBytes + i) = wordsB(i)

  private def program(readerAddr: Int, writerAddr: Int): Unit = {
    control.writePush(Register.ReaderStartAddr, readerAddr)
    control.writePush(Register.ReaderLineLen, readLineWords)
    control.writePush(Register.ReaderLineCnt, 1)
    control.writePush(Register.ReaderStride, 0)
    control.writePush(Register.WriterStartAddr, writerAddr)
    control.writePush(Register.WriterLineLen, writeLineWords)
    control.writePush(Register.WriterLineCnt, 1)
    control.writePush(Register.WriterStride, 0)
    control.writePush(Register.Direction, 0)
  }

  control.writePush(Register.InterruptMask, 3)

  // Arm transfer 1, then immediately program and arm transfer 2 -- no wait.
  program(0, 0)
  control.writePush(Register.Ctrl, 0xf)
  program(4096, 4096)
  control.writePush(Register.Ctrl, 0xf)

  private val expA = WordPacking.bytesToWords(tagA, writeBytes)
  private val expB = WordPacking.bytesToWords(tagB, writeBytes)
  private val destWordA = 0
  private val destWordB = 4096 / writeBytes
  private val lastA = destWordA + writeLineWords - 1
  private val lastB = destWordB + writeLineWords - 1

  // Wait for the last word of BOTH transfers (the last word of transfer 2 is
  // only written once transfer 2 completes).
  var cycles = 0
  while (
    (writer.buf(lastA) != expA(writeLineWords - 1) || writer.buf(lastB) != expB(writeLineWords - 1)) &&
    cycles < 200000
  ) {
    step(1)
    cycles += 1
  }
  step(200)

  for (j <- expA.indices) {
    val got = writer.buf(destWordA + j) & writeMask
    val exp = expA(j) & writeMask
    assert(got == exp, s"transfer 1 word $j: got 0x${got.toString(16)}, expected 0x${exp.toString(16)}")
  }
  for (j <- expB.indices) {
    val got = writer.buf(destWordB + j) & writeMask
    val exp = expB(j) & writeMask
    assert(got == exp, s"transfer 2 word $j: got 0x${got.toString(16)}, expected 0x${exp.toString(16)}")
  }
}
