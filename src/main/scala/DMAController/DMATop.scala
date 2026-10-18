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

package DMAController

import chisel3._
import chisel3.util._
import DMAController.Bus._
import DMAController.CSR._
import DMAController.Frontend._
import DMAController.Worker.{InterruptBundle, WorkerCSRWrapper, SyncBundle}
import DMAController.DMAConfig._
import DMAUtils._

class DMATop(implicit dmaConfig: DMAConfig) extends DMAModule {
  val io = IO(new Bundle {
    val control = Bus.getControlBus
    val read = Bus.getReaderBus
    val write = Bus.getWriterBus
    val irq = new InterruptBundle
    val sync = new SyncBundle
  })

  val csrFrontend = Module(Bus.getCSR)

  val csr = Module(new CSR)

  val ctl = Module(new WorkerCSRWrapper)

  csrFrontend.io.ctl <> io.control
  csr.io.bus <> csrFrontend.io.bus
  ctl.io.csr <> csr.io.csr

  io.irq <> ctl.io.irq
  io.sync <> ctl.io.sync

  if (!dmaConfig.canFlip) {
    // Single-direction datapath (original behaviour).
    val readerFrontend = Module(Bus.getReader)
    val writerFrontend = Module(Bus.getWriter)

    val queue = DMAQueue(readerFrontend.io.dataIO)

    if (dmaConfig.sourceWidth == dmaConfig.destWidth) {
      queue <> writerFrontend.io.dataIO
    } else {
      val widthConverter = Module(new WidthConverter(dmaConfig.sourceWidth, dmaConfig.destWidth))
      widthConverter.io.in <> queue
      writerFrontend.io.dataIO <> widthConverter.io.out
    }

    readerFrontend.io.xfer <> ctl.io.xferRead
    writerFrontend.io.xfer <> ctl.io.xferWrite

    io.read <> readerFrontend.io.bus
    io.write <> writerFrontend.io.bus
  } else {
    // Bidirectional datapath. Both physical ports are fixed AXI4 masters; the
    // runtime `Direction` register selects which one is the source:
    //   direction = 0: `io.read`  (sourceWidth) -> `io.write` (destWidth)
    //   direction = 1: `io.write` (destWidth)   -> `io.read`  (sourceWidth)
    // Config used by the reverse datapath; widths are swapped.
    val reverseConfig = new DMAConfig(
      dmaConfig.busConfig,
      dmaConfig.addrWidth,
      dmaConfig.destWidth,
      dmaConfig.sourceWidth,
      dmaConfig.readMaxBurst,
      dmaConfig.writeMaxBurst,
      dmaConfig.reader4KBarrier,
      dmaConfig.writer4KBarrier,
      dmaConfig.controlDataWidth,
      dmaConfig.controlAddrWidth,
      dmaConfig.controlRegCount,
      dmaConfig.fifoDepth
    )

    val readerF = Module(Bus.getReader(dmaConfig))
    val writerF = Module(Bus.getWriter(dmaConfig))
    val readerR = Module(Bus.getReader(reverseConfig))
    val writerR = Module(Bus.getWriter(reverseConfig))

    val queueF = DMAQueue(readerF.io.dataIO)
    val queueR = DMAQueue(readerR.io.dataIO)(reverseConfig)

    if (dmaConfig.sourceWidth == dmaConfig.destWidth) {
      queueF <> writerF.io.dataIO
      queueR <> writerR.io.dataIO
    } else {
      val convF = Module(new WidthConverter(dmaConfig.sourceWidth, dmaConfig.destWidth))
      convF.io.in <> queueF
      writerF.io.dataIO <> convF.io.out

      val convR = Module(new WidthConverter(dmaConfig.destWidth, dmaConfig.sourceWidth)(reverseConfig))
      convR.io.in <> queueR
      writerR.io.dataIO <> convR.io.out
    }

    val direction = ctl.io.isReverse
    val forward = !direction

    // Source descriptor -> active reader.
    readerF.io.xfer.valid := ctl.io.xferRead.valid && forward
    readerF.io.xfer.address := ctl.io.xferRead.address
    readerF.io.xfer.length := ctl.io.xferRead.length
    readerF.io.xfer.first := ctl.io.xferRead.first

    readerR.io.xfer.valid := ctl.io.xferRead.valid && direction
    readerR.io.xfer.address := ctl.io.xferRead.address
    readerR.io.xfer.length := ctl.io.xferRead.length
    readerR.io.xfer.first := ctl.io.xferRead.first

    ctl.io.xferRead.done := Mux(direction, readerR.io.xfer.done, readerF.io.xfer.done)

    // Destination descriptor -> active writer.
    writerF.io.xfer.valid := ctl.io.xferWrite.valid && forward
    writerF.io.xfer.address := ctl.io.xferWrite.address
    writerF.io.xfer.length := ctl.io.xferWrite.length
    writerF.io.xfer.first := ctl.io.xferWrite.first

    writerR.io.xfer.valid := ctl.io.xferWrite.valid && direction
    writerR.io.xfer.address := ctl.io.xferWrite.address
    writerR.io.xfer.length := ctl.io.xferWrite.length
    writerR.io.xfer.first := ctl.io.xferWrite.first

    ctl.io.xferWrite.done := Mux(direction, writerR.io.xfer.done, writerF.io.xfer.done)

    // Physical port muxing.
    val readBus = io.read.asInstanceOf[AXI4]
    val writeBus = io.write.asInstanceOf[AXI4]

    val readerFBus = readerF.io.bus.asInstanceOf[AXI4]
    val writerFBus = writerF.io.bus.asInstanceOf[AXI4]
    val readerRBus = readerR.io.bus.asInstanceOf[AXI4]
    val writerRBus = writerR.io.bus.asInstanceOf[AXI4]

    // io.read: forward -> readerF, reverse -> writerR
    broadcastInputs(readerFBus, readBus)
    broadcastInputs(writerRBus, readBus)
    muxOutputs(direction, readBus, readerFBus, writerRBus)

    // io.write: forward -> writerF, reverse -> readerR
    broadcastInputs(writerFBus, writeBus)
    broadcastInputs(readerRBus, writeBus)
    muxOutputs(direction, writeBus, writerFBus, readerRBus)
  }

  /** Forward every master input of `src` (the top-level port) into `dst`. */
  private def broadcastInputs(dst: AXI4, src: AXI4): Unit = {
    dst.aw.awready := src.aw.awready
    dst.w.wready := src.w.wready
    dst.b.bid := src.b.bid
    dst.b.bresp := src.b.bresp
    dst.b.bvalid := src.b.bvalid
    dst.ar.arready := src.ar.arready
    dst.r.rid := src.r.rid
    dst.r.rdata := src.r.rdata
    dst.r.rresp := src.r.rresp
    dst.r.rlast := src.r.rlast
    dst.r.rvalid := src.r.rvalid
  }

  /** Mux the master outputs of `a` (selected when `sel` is false) and `b`
    * (selected when `sel` is true) onto the top-level port `dst`. */
  private def muxOutputs(sel: Bool, dst: AXI4, a: AXI4, b: AXI4): Unit = {
    dst.aw.awid := Mux(sel, b.aw.awid, a.aw.awid)
    dst.aw.awaddr := Mux(sel, b.aw.awaddr, a.aw.awaddr)
    dst.aw.awlen := Mux(sel, b.aw.awlen, a.aw.awlen)
    dst.aw.awsize := Mux(sel, b.aw.awsize, a.aw.awsize)
    dst.aw.awburst := Mux(sel, b.aw.awburst, a.aw.awburst)
    dst.aw.awlock := Mux(sel, b.aw.awlock, a.aw.awlock)
    dst.aw.awcache := Mux(sel, b.aw.awcache, a.aw.awcache)
    dst.aw.awprot := Mux(sel, b.aw.awprot, a.aw.awprot)
    dst.aw.awqos := Mux(sel, b.aw.awqos, a.aw.awqos)
    dst.aw.awvalid := Mux(sel, b.aw.awvalid, a.aw.awvalid)

    dst.w.wdata := Mux(sel, b.w.wdata, a.w.wdata)
    dst.w.wstrb := Mux(sel, b.w.wstrb, a.w.wstrb)
    dst.w.wlast := Mux(sel, b.w.wlast, a.w.wlast)
    dst.w.wvalid := Mux(sel, b.w.wvalid, a.w.wvalid)

    dst.b.bready := Mux(sel, b.b.bready, a.b.bready)

    dst.ar.arid := Mux(sel, b.ar.arid, a.ar.arid)
    dst.ar.araddr := Mux(sel, b.ar.araddr, a.ar.araddr)
    dst.ar.arlen := Mux(sel, b.ar.arlen, a.ar.arlen)
    dst.ar.arsize := Mux(sel, b.ar.arsize, a.ar.arsize)
    dst.ar.arburst := Mux(sel, b.ar.arburst, a.ar.arburst)
    dst.ar.arlock := Mux(sel, b.ar.arlock, a.ar.arlock)
    dst.ar.arcache := Mux(sel, b.ar.arcache, a.ar.arcache)
    dst.ar.arprot := Mux(sel, b.ar.arprot, a.ar.arprot)
    dst.ar.arqos := Mux(sel, b.ar.arqos, a.ar.arqos)
    dst.ar.arvalid := Mux(sel, b.ar.arvalid, a.ar.arvalid)

    dst.r.rready := Mux(sel, b.r.rready, a.r.rready)
  }
}
