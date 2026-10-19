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
  * Directed test for the `Start misaligned` status bit: a start address that is
  * not aligned to its own frontend's data-bus width is flagged, an aligned one
  * is not, and writing Control clears the sticky flag. Each side has its own
  * granularity (4 B for the 32-bit CPU side, 64 B for the 512-bit island side),
  * so a 4-byte-aligned CPU source is valid.
  */
class AxiTopMisalignTest(dut: DMATop)(implicit val dmaConfig: DMAConfig)
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

  private val lineBytes = writeBytes
  private val readLineWords = lineBytes / readBytes
  private val bufWords = 4096

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

  private def arm(readerAddr: Int, writerAddr: Int): Unit = {
    control.writePush(Register.ReaderStartAddr, readerAddr)
    control.writePush(Register.ReaderLineLen, readLineWords)
    control.writePush(Register.ReaderLineCnt, 1)
    control.writePush(Register.ReaderStride, 0)
    control.writePush(Register.WriterStartAddr, writerAddr)
    control.writePush(Register.WriterLineLen, 1)
    control.writePush(Register.WriterLineCnt, 1)
    control.writePush(Register.WriterStride, 0)
    control.writePush(Register.Direction, 0)
    step(200)

    control.writePush(Register.InterruptMask, 3)
    control.writePush(Register.Ctrl, 0xf)
    step(60)
  }

  private def drainResponses(): Unit = {
    while (control.getResponse().isDefined) {}
  }

  private def readReg(addr: BigInt): BigInt = {
    // Every write also pushes a Response, so clear the queue before reading to
    // make sure the value returned is the read's.
    drainResponses()
    control.readPush(addr)
    step(60)
    control.getResponse().map(_.rd_data).getOrElse(BigInt(-1))
  }

  private def readStatus(): BigInt = readReg(Register.Status)

  private def misalignedBit(status: BigInt): BigInt = (status >> 3) & 1

  // Writer (512-bit island) must be aligned to its own 64-byte granularity.
  arm(readerAddr = 0, writerAddr = 4)
  val misalignedStatus = readStatus()
  assert(misalignedBit(misalignedStatus) == 1,
    f"Start misaligned bit not set for island addr 4: status=0x$misalignedStatus%x")

  // Writing Control clears the sticky flag.
  control.writePush(Register.Ctrl, 0x0)
  step(60)
  val clearedStatus = readStatus()
  assert(misalignedBit(clearedStatus) == 0,
    f"Start misaligned bit not cleared by Control write: status=0x$clearedStatus%x")

  // Fully aligned start is clean.
  arm(readerAddr = 0, writerAddr = 0)
  val alignedStatus = readStatus()
  assert(misalignedBit(alignedStatus) == 0,
    f"Start misaligned bit set for fully aligned start: status=0x$alignedStatus%x")

  // A 4-byte-aligned CPU source is fine: the reader only needs its own 4-byte
  // granularity, not the island's 64-byte one.
  arm(readerAddr = 4, writerAddr = 0)
  val sourceAlignedStatus = readStatus()
  assert(misalignedBit(sourceAlignedStatus) == 0,
    f"Start misaligned bit set for 4-byte-aligned CPU source: status=0x$sourceAlignedStatus%x")

  // A reader start that is not aligned to its own 4-byte width is flagged.
  arm(readerAddr = 2, writerAddr = 0)
  val sourceMisalignedStatus = readStatus()
  assert(misalignedBit(sourceMisalignedStatus) == 1,
    f"Start misaligned bit not set for CPU addr 2: status=0x$sourceMisalignedStatus%x")
}
