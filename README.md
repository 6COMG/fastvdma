Fast Versatile DMA
==================

Copyright (c) 2019-2025 [Antmicro](https://www.antmicro.com)

Copyright (c) 2026 [6COM](https://6com.group)

Overview
--------

FastVDMA is a DMA controller designed with portability and customizability in mind.

Changes relative to upstream FastVDMA
-------------------------------------

This repository is a 6COM fork of Antmicro's FastVDMA, based on the upstream
snapshot `238de99`. The
substantive differences from that original are:

- **Asymmetric read/write data widths.** Upstream configured a single
  `readDataWidth`/`writeDataWidth` (fixed at 32 bits and asserted equal); here
  they are `sourceWidth`/`destWidth`, default to **64 bits**, and may differ
  (e.g. 64-in / 512-out). An elastic [`WidthConverter`](src/main/scala/DMAUtils/WidthConverter.scala)
  bridges the two streams while preserving the byte order (little-endian, per
  AXI), and the address generator and transfer splitter take a runtime
  `wordShift` so address stepping follows the active width.
- **Runtime-selectable transfer direction.** For AXI4<->AXI4 cores `DMATop`
  builds both datapaths and a 1-bit `Direction` register (`0x38`) selects which
  physical port is the source at runtime. Upstream had a single fixed direction
  per build; see [Direction](#direction).
- **Full-rate asymmetric conversion.** The width converter is elastic (no
  per-word turnaround bubble), so asymmetric transfers sustain the narrower bus
  instead of dropping to `ratio/(ratio+1)` of it; see
  [Asymmetric widths and performance](#asymmetric-widths-and-performance).
- **Robust multi-transfer re-arm (default).** Each address generator keeps a
  depth-1 pending start: a transfer armed while the previous one is still in
  flight is snapshotted (descriptor + geometry) and launched automatically once
  the bus goes idle, so software no longer has to wait for the busy bits to clear
  and a start can never be silently dropped. A start written while one is already
  queued is dropped and flagged by the new **Start overrun** status bit
  (Status `0x04`, bit 2); see
  [RegisterMap](docs/RegisterMap.md#status-register-0x04).
- **Configuration in code, not JSON.** The upstream JSON configuration file
  (`make verilog CONFIG_FILE=...` and `DMAParseInput`) was removed. Bus
  configuration and widths are edited directly in
  [`DMAConfig`](src/main/scala/DMAController/DMAConfig.scala).
- **Default data width** changed from 32 to **64 bits**.
- **Verification additions.** AXI top-wrapper integrity tests
  (`AxiTopSpec`/`AxiTopMemoryTest`), a runtime-[direction](#direction) suite, and
  little-endian (AXI) `WordPacking` helpers/specs were added, along with a
  directed [`WidthConverterSpec`](src/test/scala/DMAController/WidthConverterSpec.scala)
  that pins the lane order, and two standalone sbt configurations: a throughput
  **speed** test (`Speed`, see [Speed test](#speed-test)) and a data-integrity
  **stress** matrix (`Stress`). The AXI4 memory bus-functional-model handshake
  logic was also corrected so beats are not dropped/duplicated under
  backpressure.

Supported features
------------------

- Interrupts
- 2D transfers with configurable stride
- External frame synchronization inputs
- Runtime-selectable transfer direction for AXI4<->AXI4 cores

Supported buses
---------------

- Data
  - AXI4
  - AXI-Stream
  - Wishbone
- Control
  - AXI4-Lite
  - Wishbone

Data width
----------

FastVDMA moves data from a **source** bus to a **destination** bus. The width of
each is configurable and defaults to **64 bits**. It is controlled by the
`sourceWidth` and `destWidth` fields of `DMAConfig`. Each must be a multiple of 8
(a whole number of bytes), and one must be an integer multiple of the other; the
source and destination widths **may differ** (for example a 64-bit source bus
feeding a 512-bit destination bus). When they differ, a width converter bridges
the two streams inside `DMATop`.

By default the source is the AXI4 read port (`io.read`, width `sourceWidth`) and
the destination is the AXI4 write port (`io.write`, width `destWidth`); see
[Direction](#direction) to swap them at runtime.

To change the width for the default build, edit the defaults in
[`src/main/scala/DMAController/DMAConfig.scala`](src/main/scala/DMAController/DMAConfig.scala):

```scala
val sourceWidth: Int = 64,
val destWidth: Int = 64,
```

For example, set both to `32` for a 32-bit data bus, or use `sourceWidth: 64`
with `destWidth: 512` for an asymmetric configuration, then run:

```bash
make verilog
```

which always writes the generated netlist to `DMATopAXI_AXIL_AXI.sv`.

The data width affects the AXI `awsize`/`arsize` and `wstrb` signals, the address
stepping done by the address generator and transfer splitter, and the test
bus-functional models, all of which derive their behavior from this setting.

Direction
---------

When both data buses are AXI4 master ports (`AXI_*_AXI` configurations) `DMATop`
builds both datapaths and exposes a 1-bit `Direction` register at `0x38`. It
selects which physical port is the source at runtime:

| `Direction` | Source                    | Destination               |
|-------------|---------------------------|---------------------------|
| `0` (reset) | `io.read` (`sourceWidth`) | `io.write` (`destWidth`)  |
| `1`         | `io.write` (`destWidth`)  | `io.read` (`sourceWidth`) |

This lets a single build move data in either direction over the same fixed-width
physical buses, so a `32<->512` core can do both 32-to-512 and 512-to-32 without
a second netlist. Only the selected direction is active per transfer; write the
register before setting the control register. For configurations that are not
AXI4<->AXI4 the datapath is single-directional and the register is ignored.

Asymmetric widths and performance
---------------------------------

When `sourceWidth` and `destWidth` are equal, `DMATop` wires the reader and
writer together directly and **no conversion logic is added**, so there is no
throughput or area cost.

When they differ, an elastic
[`WidthConverter`](src/main/scala/DMAUtils/WidthConverter.scala) bridges the two
streams. It re-chunks the byte stream without reordering it, so the transfer is
functionally identical. A completing input is deposited straight into a one-deep
output skid (widening) or a fresh input is loaded on the cycle the last chunk
leaves (narrowing), so the converter accepts one input word every cycle whenever
the downstream is ready and inserts **no per-word bubble**. Fed by the deep DMA
FIFO it is therefore transparent and sustains the **full beat rate of the
narrower bus** — the only residual cost is up to `ratio - 1` cycles of fill
latency before the first output word. This only matters when the source can
supply a beat every cycle; real transfers are usually limited by memory latency
first.

Byte order
----------

The converter is **little-endian**, following the AXI convention that the byte
at the lowest address occupies the low-order data lanes. When widening, the
first input word (lowest address) lands in the **least-significant bits** of the
output word; when narrowing, the least-significant chunk is emitted first. This
is the same order used by
[`WordPacking`](src/test/scala/DMAController/Bfm/WordPacking.scala) in the test
harness. It matters at wide asymmetric crossings (e.g. a `32<->64` conversion):
a byte-stream-preserving little-endian mapping keeps the 32-bit lanes in address
order. [`WidthConverterSpec`](src/test/scala/DMAController/WidthConverterSpec.scala)
pins this mapping with directed tests, independent of the round-trip oracle.

Speed test
----------

A throughput speed test is included as
[`AxiTopSpeedSpec`](src/speed/scala/DMAController/AxiTopSpeedSpec.scala).
It lives in its own `speed` sbt configuration
([`src/speed/scala`](src/speed/scala)) and is therefore **not** part of
the default `sbt test` run. It moves a contiguous **4 KiB** buffer through the
AXI top wrapper (`DMATopAXI_AXIL_AXI`) with back-to-back memory bus-functional
models (one data beat per cycle each) and counts the clock cycles from the `Ctrl`
write until the writer-done interrupt. Maximal AXI burst lengths are used, so the
numbers reflect steady-state throughput. Run it with either:

```bash
make speed
# or
sbt "Speed / test"
```

It writes the rows to `speed_results.csv` in the working directory. The
results below were produced with the `dma_dev` container image:

| Read / write width | Transfer | Cycles | Bytes/cycle | Efficiency¹ |
|--------------------|---------:|-------:|------------:|------------:|
| 32 / 32 bit        |   4 KiB  |  1083  | 3.78        | 94.6%       |
| 64 / 64 bit        |   4 KiB  |   571  | 7.17        | 89.7%       |
| 64 / 128 bit       |   4 KiB  |   569  | 7.20        | 90.0%       |
| 128 / 64 bit (rev) |   4 KiB  |   572  | 7.16        | 89.5%       |

¹ Relative to the theoretical peak of the narrower bus (`dataWidth / 8` bytes
per cycle).

With the elastic converter the asymmetric rows sustain the narrower bus just like
the equal-width build, so **64/128 and 128/64 match 64/64** (~7.2 bytes/cycle)
instead of dropping to ~62%. At the default **64-bit** width the equal-width
configuration reaches about **7.17 bytes/cycle**, i.e. roughly
**717 MB/s** at 100 MHz or **1.79 GB/s** at 250 MHz when the memory can sustain a
beat every cycle.

Dependencies
------------

Because the controller is written in Chisel, it requires `sbt`, `scala` and `java` to be installed; additionally the tests require `imagemagick`.

Simulation
----------

FastVDMA can be simulated as a whole but certain components can be tested separately.

You can simulate the full memory to memory design by running:

`make testM2M`

And the full stream to memory test by:

`make testS2M`

Test runs can generate `.vcd` files that can be opened using GTKWave or any
other `.vcd` viewer; output files are located in separate subdirectories inside
the `test_run_dir` directory. Dumping waveforms for the full 256x256 image
transfers in `ControllerSpec` produces tens of megabytes per test and dominates
the runtime, so it is disabled by default and enabled with:

```bash
DUMP_VCD=1 make testM2M
```

The full test should generate an `outM2M.png/outS2M.png` file demonstrating a 2D transfer with configurable stride. The resulting image should look similar to:

![Reference image](docs/ref-output.png)

Synthesis
---------

To generate a synthesizable verilog file run:

`make verilog`

The configuration (bus types, data widths, etc.) is edited in the Chisel
[`DMAConfig`](src/main/scala/DMAController/DMAConfig.scala) class; see the
[configuration documentation](docs/Configuration.md).

The generated file will be named `DMATop$(configuration).sv` where `configuration`
is the chosen configuration of buses in the DMA, e.g. `DMATopAXI_AXIL_AXI.sv` for
the default `AXI_AXIL_AXI` configuration. The Verilog module is named in the same
manner.

Source code structure
---------------------
- [src/main/scala/DMAController](src/main/scala/DMAController) contains sources of the DMA controller
  - [Bus](src/main/scala/DMAController/Bus) contains definitions of various bus bundles
  - [CSR](src/main/scala/DMAController/CSR) contains code responsible for handling configuration registers
  - [Frontend](src/main/scala/DMAController/Frontend) contains modules handling various bus types
  - [Worker](src/main/scala/DMAController/Worker) contains generic code supporting controlling the DMA behavior
- [src/test/scala/DMAController](src/test/scala/DMAController) contains tests
  - [Bfm](src/test/scala/DMAController/Bfm) contains Bus models that are used in full configuration tests
  - [Frontend](src/test/scala/DMAController/Frontend) contains tests used for generating timing diagrams for various bus types
  - [Worker](src/test/scala/DMAController/Worker) contains tests that generate timing diagrams for the generic part of the DMA
