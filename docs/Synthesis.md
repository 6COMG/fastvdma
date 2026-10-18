# Synthesis

To generate a synthesizable verilog file proceed with the default configuration by running:

```bash
make verilog
```

The configuration (bus types, data widths, ...) is edited in the Chisel
[`DMAConfig`](../src/main/scala/DMAController/DMAConfig.scala) class; see
[Configuration](Configuration.md).

The generated file will be named `DMATop$(configuration).sv` where `configuration`
is the chosen configuration of buses in the DMA. The Verilog module will be named
in the same manner, e.g. `DMATopAXI_AXIL_AXI.sv` for the default `AXI_AXIL_AXI`
configuration.
