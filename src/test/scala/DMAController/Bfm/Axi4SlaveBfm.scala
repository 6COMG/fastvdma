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

package DMAController.Bfm

import DMAController.Bus._
import chisel3.Bits

class Axi4MemoryBfm(val axi: AXI4,
                        val size: Int,
                        val peek: Bits => BigInt,
                        val poke: (Bits, BigInt) => Unit,
                        val println: String => Unit)
extends Axi4Bfm {

  // Number of bytes carried by a single data beat. Addresses are byte
  // addresses, so the word index is `address / dataBytes`.
  val dataBytes: Int = axi.dataWidth / 8

  var buf: Array[BigInt] = Array.fill(size)(BigInt(0))

  class Write {
    private object State extends Enumeration {
      type State = Value
      val Idle, WriteAddr, WriteData, WriteResp = Value
    }

    private var state = State.Idle

    private var awaddr: BigInt = 0
    private var awlen: BigInt = 0
    private var awvalid: BigInt = 0

    private var wdata: BigInt = 0
    private var wlast: BigInt = 0
    private var wvalid: BigInt = 0

    private var bready: BigInt = 0

    private var addr: Int = 0
    private var len: Int = 0
    private var xferLen: Int = 0

    private def peekInputs(): Unit = {
      awaddr = peek(axi.aw.awaddr)
      awlen = peek(axi.aw.awlen)
      awvalid = peek(axi.aw.awvalid)

      wdata = peek(axi.w.wdata)
      wlast = peek(axi.w.wlast)
      wvalid = peek(axi.w.wvalid)

      bready = peek(axi.b.bready)
    }

    def update(t: Long): Unit = {
      state match {
        case State.Idle => {
          poke(axi.aw.awready, 1)
          poke(axi.b.bvalid, 0)
          state = State.WriteAddr
          len = 0
        }
        case State.WriteAddr => {
          if(awvalid != 0) {
            addr = (awaddr / dataBytes).toInt
            xferLen = awlen.toInt + 1
            poke(axi.aw.awready, 0)
            // Keep `wready` low until WriteData so the first accepted beat is
            // the one observed in the same cycle there.
            poke(axi.w.wready, 0)
            state = State.WriteData
          }
        }
        case State.WriteData => {
          // `wready` is asserted here (not in WriteAddr), so consuming on the
          // same-cycle `wvalid` observes exactly the beat accepted this edge,
          // even when the master leaves gaps between beats.
          poke(axi.w.wready, 1)
          if(peek(axi.w.wvalid) != 0) {
            buf(addr) = peek(axi.w.wdata)
            addr += 1
            len += 1
            if(peek(axi.w.wlast) != 0) {
              // Leave `wready` high through this final beat's handshake; it is
              // dropped in WriteResp so the last beat is not cancelled.
              poke(axi.b.bvalid, 1)
              poke(axi.b.bresp, 0)
              state = State.WriteResp
              if(xferLen != len) {
                println("Transfer len doesn't match, %d %d".format(xferLen, len))
              }
            }
          }
        }
        case State.WriteResp => {
          poke(axi.w.wready, 0)
          // Hold `bvalid` until the master accepts it, then return to Idle
          // (where `bvalid` is deasserted).
          poke(axi.b.bvalid, 1)
          if(bready != 0) {
            state = State.Idle
          }
        }
      }
      peekInputs()
    }
  }

  class Read {
    private object State extends Enumeration {
      type State = Value
      val Idle, ReadData = Value
    }

    // Within a burst, either the next beat still has to be driven, or the
    // current beat is already on the bus and is waiting to be accepted.
    private object Phase extends Enumeration {
      type Phase = Value
      val NeedPresent, Holding = Value
    }

    private var state = State.Idle
    private var phase = Phase.NeedPresent

    private var araddr: BigInt = 0
    private var arlen: BigInt = 0
    private var arvalid: BigInt = 0

    private var rready: BigInt = 0

    private var idx: Int = 0
    private var baseAddr: Int = 0
    private var xferLen: Int = 0

    private def peekInputs(): Unit = {
      araddr = peek(axi.ar.araddr)
      arlen = peek(axi.ar.arlen)
      arvalid = peek(axi.ar.arvalid)

      rready = peek(axi.r.rready)
    }

    def update(t: Long): Unit = {
      // Sample the current cycle's inputs first: acceptance must be evaluated in
      // the same cycle the beat is driven, otherwise beats are dropped or
      // duplicated whenever the master applies backpressure.
      peekInputs()
      state match {
        case State.Idle => {
          poke(axi.ar.arready, 1)
          poke(axi.r.rvalid, 0)
          poke(axi.r.rlast, 0)
          if(arvalid != 0) {
            baseAddr = (araddr / dataBytes).toInt
            xferLen = arlen.toInt + 1
            idx = 0
            phase = Phase.NeedPresent
            state = State.ReadData
          }
        }
        case State.ReadData => {
          // Drop `arready` only after the address handshake has been observed.
          poke(axi.ar.arready, 0)
          phase match {
            case Phase.NeedPresent => {
              val last = idx == xferLen - 1
              poke(axi.r.rdata, buf(baseAddr + idx))
              poke(axi.r.rlast, if(last) 1 else 0)
              poke(axi.r.rvalid, 1)
              if(rready != 0) {
                idx += 1
                if(idx == xferLen) {
                  // Leave the final (rlast) beat on the bus for this edge; it
                  // is deasserted when the FSM returns to Idle next cycle.
                  state = State.Idle
                }
              } else {
                phase = Phase.Holding
              }
            }
            case Phase.Holding => {
              // Beat already on the bus; leave it untouched until accepted.
              if(rready != 0) {
                idx += 1
                if(idx == xferLen) {
                  state = State.Idle
                } else {
                  phase = Phase.NeedPresent
                }
              }
            }
          }
        }
      }
    }
  }

  def loadFromFile(filename: String): Unit = {
    buf = WordPacking.loadWords(filename, dataBytes)
  }

  def saveToFile(filename: String): Unit = {
    WordPacking.saveWords(filename, buf, dataBytes)
  }

  private val writer = new Write()
  private val reader = new Read()

  def update(t: Long): Unit = {
    writer.update(t)
    reader.update(t)
  }
}
