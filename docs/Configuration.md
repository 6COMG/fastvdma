# Configuration

## Configuration

FastVDMA is configured in Chisel, in the
[`DMAConfig`](../src/main/scala/DMAController/DMAConfig.scala) class. Edit its
constructor arguments to change the generated core, then rebuild with:

```bash
make verilog
```

For example, an AXI4 source / AXI4 destination core with a 64-bit source bus and
a 128-bit destination bus:

```scala
new DMAConfig(
    busConfig = "AXI_AXIL_AXI",
    addrWidth = 32,
    sourceWidth = 64,
    destWidth = 128,
    readMaxBurst = 256,   // split read bursts (AXI4: <=256 beats, no 4 KiB crossing)
    writeMaxBurst = 16,
    reader4KBarrier = false,
    writer4KBarrier = true,
    controlDataWidth = 32,
    controlAddrWidth = 32,
    controlRegCount = 16,
    fifoDepth = 512
)
```

## Configuration parameters

### configuration

This field is used to specify the FastVDMA bus configuration. It's a string of format:

```
BUS-IN_BUS-CSR_BUS-OUT
```

Where the `BUS-IN` and `BUS-OUT` are the data transferring buses and `BUS-CSR` is the CSR handling bus type.

List of all supported DMA bus configurations is available in the [DMAConfig](https://github.com/antmicro/fastvdma/blob/main/src/main/scala/DMAController/DMAConfig.scala) file.

---

### addrWidth

This field specifies address width of the data transferring buses.

---

### sourceWidth

This field specifies the data width of the source bus (`BUS-IN`, the AXI4 read
port `io.read` in the default direction).

:::{admonition}
The value must be a multiple of **8** (a whole number of bytes). It does not have
to equal `destWidth`; the two widths may differ as long as one is an integer
multiple of the other (a width converter bridges them). Supported values include
**32**, **64**, **128** and **512**.
:::

---

### destWidth

This field specifies the data width of the destination bus (`BUS-OUT`, the AXI4
write port `io.write` in the default direction).

:::{admonition}
The value must be a multiple of **8** (a whole number of bytes) and an integer
multiple of `sourceWidth` (or vice versa). Supported values include **32**,
**64**, **128** and **512**.
:::

:::{admonition}
When both data buses are AXI4 master ports (`AXI_*_AXI`), `DMATop` builds both
datapaths and the runtime `Direction` register (`0x38`) swaps the source and
destination. See the [register map](RegisterMap.md#direction-register-0x38).
:::

:::{admonition}
:class: note
Asymmetric widths add integration constraints: each side's start address must be
aligned to its own bus width (for example 8 B for 64-bit, 16 B for 128-bit), line
lengths are expressed in that side's bus words, and AXI4 bursts must not exceed
256 beats or cross 4 KiB. See [Width-related
constraints](../README.md#width-related-constraints) and
[`readMaxBurst`](#readmaxburst).
:::

---

### readMaxBurst

This field specifies the length of the longest single read burst, in **data
beats** (bus words) of the `BUS-IN`/source side — the same unit as the reader
line length.

:::{admonition}
The `0` value is reserved for the stream interfaces and **disables the transfer
splitter** (the whole line is issued as one burst).
:::

:::{admonition}
:class: warning
AXI4 bursts must not exceed **256 beats** and must not cross a **4 KiB**
boundary. On a wide source bus these limits are reached quickly — a 128-bit
(16 B) beat crosses 4 KiB after only **256 beats**. Set `readMaxBurst` to a
non-zero value (≤ 256) whenever a source line can exceed the bus limits, in
particular for a wide source bus, which is `BUS-IN` under `Direction = 1`.
Leaving it at `0` in that case issues an out-of-spec burst that hangs or aliases.
:::

---

### writeMaxBurst

This field specifies the length of the longest single write burst, in **data
beats** (bus words) of the `BUS-OUT`/destination side.

:::{admonition}
:class: warning
As with `readMaxBurst`, an AXI4 burst must not exceed **256 beats** or cross a
**4 KiB** boundary. The default (`16`) is non-zero and safe for the usual case;
raise it only within the 256-beat limit.
:::

---

### reader4KBarrier

This field selects whether the `BUS-IN`/source splitter **shortens a burst at the
4 KiB boundary**. When `false` the splitter inserts a split so a burst never
crosses 4 KiB; when `true` it does not, assuming the interconnect handles it. It
has no effect when `readMaxBurst` is `0` (splitting disabled). The default is
`false` (boundary enforced).

---

### writer4KBarrier

Same as `reader4KBarrier`, for the `BUS-OUT`/destination side and `writeMaxBurst`.
The default is `true` (crossing allowed); set it to `false` if the destination
fabric does not tolerate bursts crossing a 4 KiB boundary.

---

### controlDataWidth

This field specifies the data width of the `BUS-CSR` bus.

---

### controlAddrWidth

This field specifies the address width of the `BUS-CSR` bus.

:::{admonition}
The FastVDMA was written with enhancements in mind.
It is not recommended to use address widths other than **32**.
:::

---

### controlRegCount

This field specifies the number of the registers the `BUS-CSR` handles. It needs to be at least **16**.

---

### fifoDepth

This parameter specifies the depth of the queue initialized and connected between `BUS-IN` and `BUS-OUT` interfaces.
