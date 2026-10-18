# Configuration

## Configuration

FastVDMA is configured in Chisel, in the
[`DMAConfig`](../src/main/scala/DMAController/DMAConfig.scala) class. Edit its
constructor arguments to change the generated core, then rebuild with:

```bash
make verilog
```

For example, an AXI4 source / AXI4 destination core with a 64-bit source bus and
a 512-bit destination bus:

```scala
new DMAConfig(
    busConfig = "AXI_AXIL_AXI",
    addrWidth = 32,
    sourceWidth = 64,
    destWidth = 512,
    readMaxBurst = 0,
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
**32**, **64** and **512**.
:::

---

### destWidth

This field specifies the data width of the destination bus (`BUS-OUT`, the AXI4
write port `io.write` in the default direction).

:::{admonition}
The value must be a multiple of **8** (a whole number of bytes) and an integer
multiple of `sourceWidth` (or vice versa). Supported values include **32**,
**64** and **512**.
:::

:::{admonition}
When both data buses are AXI4 master ports (`AXI_*_AXI`), `DMATop` builds both
datapaths and the runtime `Direction` register (`0x38`) swaps the source and
destination. See the [register map](RegisterMap.md#direction-register-0x38).
:::

---

### readMaxBurst

This field specifies the maximal burst for the `BUS-IN` bus. That is, the maximum number of bytes within the single transaction.

:::{admonition}
The `0`-value is reserved for the stream interfaces.
:::

---

### writeMaxBurst

This field specifies the maximal burst for the `BUS-OUT` bus. That is, the maximum number of bytes within the single transaction.

:::{admonition}
The `0`-value is reserved for the stream interfaces.
:::

---

### reader4KBarrier

This field takes the `true`/`false` value whether the `BUS-IN` interface may cross 4KB (4096 bytes) in a single transfer.

---

### writer4KBarrier

This field takes the `true`/`false` value whether the `BUS-OUT` interface may cross 4KB (4096 bytes) in a single transfer.

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
