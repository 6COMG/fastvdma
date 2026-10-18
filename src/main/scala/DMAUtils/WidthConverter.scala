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

package DMAUtils

import chisel3._
import chisel3.util._
import DMAController.DMAConfig._

/**
  * Elastic (non-bubbling) decoupled width converter.
  *
  * Re-chunks a stream of `inWidth`-bit words into `outWidth`-bit words without
  * changing the byte order of the payload: the first byte of the input stream
  * remains the first byte of the output stream. It uses little-endian (AXI)
  * byte order -- the first input word, at the lowest address, is placed in the
  * least-significant bits of the output word -- matching the packing used by
  * the bus functional models (`WordPacking`), so a transfer preserves the
  * source byte stream for any pair of (whole-byte) widths.
  *
  * Unlike a naive single-register gearbox it does not insert an idle cycle per
  * output word: a completing input is deposited straight into the output skid
  * (widening) or a fresh input is loaded on the cycle the last chunk leaves
  * (narrowing), so one input word is accepted every cycle whenever the
  * downstream is ready. Combined with the preceding FIFO this makes the
  * conversion transparent, sustaining the narrower bus' full beat rate.
  *
  * One width must be an integer multiple of the other.
  */
class WidthConverter(val inWidth: Int, val outWidth: Int)(implicit dmaConfig: DMAConfig)
    extends DMAModule {
  require(inWidth > 0 && outWidth > 0, "Widths must be positive")
  require(
    inWidth % outWidth == 0 || outWidth % inWidth == 0,
    s"Data widths must be integer multiples of each other (in=$inWidth, out=$outWidth)"
  )

  val io = IO(new Bundle {
    val in = Flipped(Decoupled(UInt(inWidth.W)))
    val out = Decoupled(UInt(outWidth.W))
  })

  if (inWidth == outWidth) {
    io.out <> io.in
  } else if (outWidth > inWidth) {
    // Widening: gather `ratio` input words and emit them as one output word.
    // Little-endian (AXI byte order): the first input word (lowest address)
    // lands in the least-significant bits of the output, preserving the byte
    // stream. This is done by shifting the accumulator down and inserting each
    // new word at the top, so after `ratio` inputs the first word sits at
    // bits [inWidth-1:0]. The completed word goes into a one-deep output skid
    // so the accumulator can immediately start the next word on the following
    // cycle (no bubble).
    val ratio = outWidth / inWidth
    val acc = Reg(UInt(outWidth.W))
    val cnt = RegInit(0.U(log2Ceil(ratio).W))
    val outReg = Reg(UInt(outWidth.W))
    val outValid = RegInit(false.B)

    val inFire = io.in.valid && io.in.ready
    val accNext = Cat(io.in.bits, acc(outWidth - 1, inWidth))

    // Accept input whenever there is room in the output skid, or it drains this
    // cycle (a completing input then overwrites the freed slot).
    io.in.ready := !outValid || io.out.ready
    io.out.valid := outValid
    io.out.bits := outReg

    // Drain the presented word first ...
    when(outValid && io.out.ready) {
      outValid := false.B
    }
    // ... then fill; a completing input takes precedence over the drain.
    when(inFire) {
      acc := accNext
      when(cnt === (ratio - 1).U) {
        outReg := accNext
        outValid := true.B
        cnt := 0.U
      }.otherwise {
        cnt := cnt + 1.U
      }
    }
  } else {
    // Narrowing: emit `ratio` output words from one input word, least-
    // significant chunk first (AXI byte order), so the byte stream is
    // preserved. A new input word is loaded on the cycle the last chunk leaves,
    // so output words are produced back-to-back.
    val ratio = inWidth / outWidth
    val buf = Reg(UInt(inWidth.W))
    val cnt = RegInit(0.U(log2Ceil(ratio).W))
    val haveData = RegInit(false.B)

    val lastChunk = cnt === (ratio - 1).U
    val inFire = io.in.valid && io.in.ready

    // Accept input when empty, or when the final chunk is consumed this cycle.
    io.in.ready := !haveData || (io.out.ready && lastChunk)
    io.out.valid := haveData
    io.out.bits := buf(outWidth - 1, 0)

    // Advance the shift register first ...
    when(haveData && io.out.ready) {
      when(lastChunk) {
        haveData := false.B
      }.otherwise {
        buf := buf >> outWidth
        cnt := cnt + 1.U
      }
    }
    // ... then load a fresh word; it takes precedence over the drain.
    when(inFire) {
      buf := io.in.bits
      cnt := 0.U
      haveData := true.B
    }
  }
}
