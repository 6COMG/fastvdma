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
  * Two back-to-back forward transfers (CPU/source bus -> island/destination
  * bus), each writing a distinct one-word tag to a distinct island address, with
  * the driver waiting for each transfer to complete before re-arming (the
  * conservative sequence). Checks both tags survive at their own addresses.
  */
class AxiTopMultiTransferTest(dut: DMATop, islandSpacingBytes: Int)(implicit val dmaConfig: DMAConfig)
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
  private val writeLineWords = lineBytes / writeBytes

  private val writeMask = (BigInt(1) << dmaConfig.destWidth) - 1
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

  private def tagBytes(base: Int): Array[Byte] =
    Array.tabulate(lineBytes)(i => ((base + i) & 0xff).toByte)

  private def runTransfer(srcBytes: Array[Byte], islandAddr: Int): Unit = {
    val srcWords = WordPacking.bytesToWords(srcBytes, readBytes)
    for (i <- srcWords.indices) reader.buf(i) = srcWords(i)

    control.writePush(Register.ReaderStartAddr, 0)
    control.writePush(Register.ReaderLineLen, readLineWords)
    control.writePush(Register.ReaderLineCnt, 1)
    control.writePush(Register.ReaderStride, 0)
    control.writePush(Register.WriterStartAddr, islandAddr)
    control.writePush(Register.WriterLineLen, writeLineWords)
    control.writePush(Register.WriterLineCnt, 1)
    control.writePush(Register.WriterStride, 0)
    control.writePush(Register.Direction, 0)

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

    control.writePush(Register.InterruptStatus, 3)
    step(50)
  }

  private val islandA = 0
  private val islandB = islandSpacingBytes

  private val tagA = tagBytes(0x10)
  private val tagB = tagBytes(0x80)

  runTransfer(tagA, islandA)
  runTransfer(tagB, islandB)

  private val expA = WordPacking.bytesToWords(tagA, writeBytes)(0) & writeMask
  private val expB = WordPacking.bytesToWords(tagB, writeBytes)(0) & writeMask

  private val gotA = writer.buf(islandA / writeBytes) & writeMask
  private val gotB = writer.buf(islandB / writeBytes) & writeMask

  assert(
    gotA == expA,
    s"island[$islandA] clobbered: got 0x${gotA.toString(16)}, expected (tag A) 0x${expA.toString(16)}"
  )
  assert(
    gotB == expB,
    s"island[$islandB] not written / aliased: got 0x${gotB.toString(16)}, expected (tag B) 0x${expB.toString(16)}"
  )
}
