# Register fields

## Register table

Current register layout is shown in the table below:

|Address | Role |
|--------|----------------------------|
|`0x00` |[Control register](control-register-0x00) |
|`0x04` |[Status register](status-register-0x04) |
|`0x08` |[Interrupt mask register](interrupt-mask-register-0x08) |
|`0x0c` |[Interrupt status register](interrupt-status-register-0x0c) |
|`0x10` |[Reader start address](reader-start-address-0x10) |
|`0x14` |[Reader line length](reader-line-length-0x14) |
|`0x18` |[Reader line count](reader-line-count-0x18) |
|`0x1c` |[Reader stride between lines](reader-stride-between-lines-0x1c) |
|`0x20` |[Writer start address](writer-start-address-0x20) |
|`0x24` |[Writer line length](writer-line-length-0x24) |
|`0x28` |[Writer line count](writer-line-count-0x28)|
|`0x2c` |[Writer stride between lines](writer-stride-between-lines-0x2c) |
|`0x30` |[Version register](version-register-0x30) |
|`0x34` |[Configuration register](configuration-register-0x34) |
|`0x38` |[Direction register](direction-register-0x38) |


## Detailed register description

### Control register (0x00)

|Bit |Name                |Description                                                                                         |
|----|--------------------|----------------------------------------------------------------------------------------------------|
|0   |Writer start        |Write `1` to start write frontend (This bit automatically resets itself to `0` if not in loop mode) |
|1   |Reader start        |Write `1` to start read frontend (This bit automatically resets itself to `0` if not in loop mode)  |
|2   |Writer sync disable |Write `1` to disable waiting for external writer synchronization (rising edge on `writerSync`)      |
|3   |Reader sync disable |Write `1` to disable waiting for external reader synchronization (rising edge on `readerSync`)      |
|4   |Writer loop mode    |Write `1` to automatically start next write frontend transfer after finishing the current one       |
|5   |Reader loop mode    |Write `1` to automatically start next read frontend transfer after finishing the current one        |
|6-31|-                   |Unused                                                                                              |

The start bits are **queued (depth 1)**: if a start is written while a transfer
is still in flight, the frontend snapshots the descriptor (start address, line
length/count/stride) and launches it automatically when the current transfer
finishes. It is therefore not necessary to wait for the [Status](#status-register-0x04)
busy bits to clear before re-arming. A start written while a previous start is
already queued (two starts in flight) is dropped; this is flagged by the
`Start overrun` status bit. Because the descriptor is snapshotted when the start
is written, the reader/writer registers may be reprogrammed for the next transfer
immediately.

---

### Status register (0x04)

|Bit |Name           |Description                                                |
|----|---------------|-----------------------------------------------------------|
|0   |Writer busy    |Reads as `1` when write frontend is busy transferring data |
|1   |Reader busy    |Reads as `1` when read frontend is busy transferring data  |
|2   |Start overrun  |Reads as `1` if a start was dropped because one was already queued; writing the Control register clears it |
|3   |Start misaligned|Reads as `1` if a start address was not aligned to the transfer granularity; writing the Control register clears it |
|4-31|-              |Unused                                                     |

The start address of each frontend must be aligned to its **own** data-bus width
(i.e. `1 << wordShift` bytes): for example 8 bytes on a 64-bit bus, 16 bytes on a
128-bit bus. Alignment is per side, so a buffer aligned to the narrow bus is valid
even when the other bus is wider. The AXI frontends issue full-width bursts, so a
start that is not size-aligned would mis-pack data. It is not blocked, but is
flagged by the `Start misaligned` status bit so integration can fail fast.

---

### Interrupt mask register (0x08)

|Bit |Name        |Description                             |
|----|------------|----------------------------------------|
|0   |Writer mask |Write `1` to enable writer interrupt    |
|1   |Reader mask |Write `1` to enable reader interrupt    |
|2-31|-           |Unused                                  |

---

### Interrupt status register (0x0c)

|Bit |Name             |Description                                                                     |
|----|-----------------|--------------------------------------------------------------------------------|
|0   |Writer interrupt |Reads as `1` if writer interrupt has occurred, write `1` to clear that interrupt |
|1   |Reader interrupt |Reads as `1` if reader interrupt has occurred, write `1` to clear that interrupt |
|2-31|-                |Unused                                                                          |

---

### Reader start address (0x10)

|Bit |Name          |Description                                                                |
|----|--------------|---------------------------------------------------------------------------|
|0-31|Start address |Reader start address (set to `0` if reader frontend is a stream interface) |

---

### Reader line length (0x14)

|Bit |Name        |Description                                              |
|----|------------|---------------------------------------------------------|
|0-31|Line length |Reader line length (as number of reader data bus widths) |

---

### Reader line count (0x18)

|Bit |Name       |Description       |
|----|-----------|------------------|
|0-31|Line count |Reader line count |

---

### Reader stride between lines (0x1c)

|Bit |Name   |Description                                                         |
|----|-------|--------------------------------------------------------------------|
|0-31|Stride |Gap between consecutive lines (as number of reader data bus widths) |

---

### Writer start address (0x20)

|Bit |Name          |Description                                                                |
|----|--------------|---------------------------------------------------------------------------|
|0-31|Start address |Writer start address (set to `0` if writer frontend is a stream interface) |

---

### Writer line length (0x24)

|Bit |Name        |Description                                              |
|----|------------|---------------------------------------------------------|
|0-31|Line length |Writer line length (as number of writer data bus widths) |

---

### Writer line count (0x28)

|Bit |Name       |Description       |
|----|-----------|------------------|
|0-31|Line count |Writer line count |

### Writer stride between lines (0x2c)

|Bit |Name   |Description                                                         |
|----|-------|--------------------------------------------------------------------|
|0-31|Stride |Gap between consecutive lines (as number of writer data bus widths) |

---

### Version register (0x30)

|Bit |Name             |Description                |
|----|-----------------|---------------------------|
|0-31|Version register |Holds the FastVDMA version |

---

### Configuration register (0x34)

|Bit |Name                   |Description                          |
|----|-----------------------|-------------------------------------|
|0-31|Configuration register |Reader, writer and control bus types |

---

### Direction register (0x38)

|Bit |Name      |Description                                                                        |
|----|----------|------------------------------------------------------------------------------------|
|0   |Direction |`0`: source is the read bus (`io.read`), destination is the write bus (`io.write`).<br>`1`: source is the write bus, destination is the read bus |
|1-31|-         |Unused                                                                              |

Selects which physical AXI4 data port acts as the source. It only has an effect
for AXI4<->AXI4 configurations (where both datapaths are built); for all other
bus configurations the datapath is single-directional and this register is
ignored. It resets to `0` (read bus is the source). Set it before starting a
transfer.

The **Reader\*** registers describe the source and the **Writer\*** registers the
destination, so at `Direction = 1` they swap roles: the 512-bit (`destWidth`) port
becomes the source and is programmed through the Reader\* registers (start
address `0x10`, line length `0x14`, ...), while the 32-bit port is programmed
through the Writer\* registers. Line lengths remain in the word size of the port
being programmed (`bytes / (width / 8)`), and each port keeps its own start
alignment (4 B for 32-bit, 64 B for 512-bit) regardless of direction.

---

You can also check [WorkerCSRWrapper](https://github.com/antmicro/fastvdma/blob/main/src/main/scala/DMAController/Worker/WorkerCSRWrapper.scala) for the implementation details on how the CSRs are attached to the DMA logic (`io.csr(0)` refers to `0x00`, `io.csr(1)` to `0x04` and so on).
