/*
Copyright (C) 2019-2025 Antmicro
Copyright (C) 2026 6COM

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

SPDX-License-Identifier: Apache-2.0
*/

package DMAController.Worker

import chisel3._
import chisel3.util._
import DMAUtils.DMAModule
import DMAController.DMAConfig._
import DMAController.DMATop

class AddressGenerator(val addrWidth: Int, val dataWidth: Int)(implicit dmaConfig: DMAConfig)
    extends DMAModule {
  val io = IO(new Bundle {
    val ctl = new AddressGeneratorCtlBundle(addrWidth)
    val xfer = new XferDescBundle(addrWidth)
  })

  val sIdle :: sLine :: sLineWait :: sDone :: Nil = Enum(4)

  val state = RegInit(sIdle)

  val lineCount = RegInit(0.U(addrWidth.W))
  val lineGap = RegInit(0.U(addrWidth.W))

  val address_o = RegInit(0.U(addrWidth.W))
  val address_i = RegInit(0.U(addrWidth.W))
  val length_o = RegInit(0.U(addrWidth.W))
  val length_i = RegInit(0.U(addrWidth.W))
  val valid = RegInit(false.B)
  val first = RegInit(false.B)
  val busy = RegInit(false.B)

  // Depth-1 pending start. A start requested while a transfer is in flight is
  // snapshotted (descriptor + geometry) and launched when the generator returns
  // to idle, so re-arming no longer has to wait for the busy signal to clear and
  // a start can never be silently dropped. A start requested while a pending one
  // is already queued is discarded and flagged via `startOverrun`.
  val startPrev = RegNext(io.ctl.start, false.B)
  val startRise = io.ctl.start && !startPrev

  val pending = RegInit(false.B)
  val pendingAddr = RegInit(0.U(addrWidth.W))
  val pendingLength = RegInit(0.U(addrWidth.W))
  val pendingCount = RegInit(0.U(addrWidth.W))
  val pendingGap = RegInit(0.U(addrWidth.W))

  // Full-width AXI bursts (see AXI4Reader/Writer) require the start address to
  // be aligned to the bus width, and the width converter has no byte offset, so
  // across a crossing the narrow stream must also start on a wider-word
  // boundary. The binding constraint is therefore the wider of the two buses;
  // flag (but do not gate) a misaligned start so integration can fail fast.
  val alignBytes = math.max(dmaConfig.sourceWidth, dmaConfig.destWidth) / 8
  val alignMask = (alignBytes - 1).U(addrWidth.W)
  val misaligned = (io.ctl.startAddress & alignMask) =/= 0.U

  io.xfer.address := address_o
  io.xfer.length := length_o
  io.xfer.valid := valid
  io.xfer.first := first
  io.ctl.busy := busy
  io.ctl.startOverrun := startRise && pending
  io.ctl.startMisaligned := startRise && misaligned

  // Busy covers both an active transfer and a queued (not yet launched) one.
  busy := (state =/= sIdle) || pending

  // Snapshot a start that arrives while a transfer is in flight.
  when(startRise && state =/= sIdle) {
    pendingAddr := io.ctl.startAddress
    pendingLength := io.ctl.lineLength
    pendingCount := io.ctl.lineCount
    pendingGap := io.ctl.lineGap
    pending := true.B
  }

  switch(state) {
    is(sIdle) {
      when(pending) {
        // Launch the queued transfer from its snapshot.
        state := sLine
        address_i := pendingAddr
        length_i := pendingLength
        lineCount := pendingCount
        lineGap := pendingGap
        first := true.B
        pending := false.B
      }.elsewhen(io.ctl.start) {
        state := sLine

        address_i := io.ctl.startAddress
        length_i := io.ctl.lineLength
        lineCount := io.ctl.lineCount
        lineGap := io.ctl.lineGap
        first := true.B
      }
    }
    is(sLine) {

      valid := true.B
      address_o := address_i
      length_o := length_i
      address_i := address_i + (length_i << io.ctl.wordShift) + (lineGap << io.ctl.wordShift)

      lineCount := lineCount - 1.U
      state := sLineWait
    }
    is(sLineWait) {
      valid := false.B
      first := false.B
      when(io.xfer.done) {
        when(lineCount > 0.U) {
          state := sLine
        }.otherwise {
          state := sIdle
        }
      }
    }
  }

}
