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

import chiseltest.ChiselScalatestTester
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import DMAController.DMAConfig._

/**
  * Runs [[AxiTopSpeedTest]] over a range of data widths. Every case moves the
  * same 4 KiB buffer so the `bytes_per_cycle` and efficiency figures are
  * directly comparable. Run with:
  *
  * {{{
  * sbt "Speed / test"
  * }}}
  */
class AxiTopSpeedSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXI top speed test"

  private val resultsFile = java.nio.file.Paths.get("speed_results.csv")

  java.nio.file.Files.write(
    resultsFile,
    "source_width,dest_width,bytes,cycles,bytes_per_cycle,efficiency\n".getBytes,
    java.nio.file.StandardOpenOption.CREATE,
    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
  )

  private def bench(config: DMAConfig, reverse: Boolean = false): Unit =
    test(new DMATop()(config)).runPeekPoke(dut => new AxiTopSpeedTest(dut, reverse)(config))

  private def cfg(read: Int, write: Int) =
    new DMAConfig(
      "AXI_AXIL_AXI",
      sourceWidth = read,
      destWidth = write,
      readMaxBurst = 256,
      writeMaxBurst = 256
    )

  it should "speed-test 32-bit read / 32-bit write" in bench(cfg(32, 32))
  it should "speed-test 64-bit read / 64-bit write" in bench(cfg(64, 64))
  it should "speed-test asymmetric 64-bit read / 128-bit write" in bench(cfg(64, 128))
  it should "speed-test reverse 128-bit read / 64-bit write" in bench(cfg(64, 128), reverse = true)
}
