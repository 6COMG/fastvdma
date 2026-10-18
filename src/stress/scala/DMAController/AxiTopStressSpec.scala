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
  * Data-integrity stress matrix over width pairs, directions, line sizes and
  * burst settings. Run with:
  *
  * {{{
  * make stress
  * # or
  * sbt "Stress / test"
  * }}}
  *
  * To run a subset, list the case indices in `STRESS_CASES`, e.g.:
  *
  * {{{
  * STRESS_CASES=2,9 sbt "Stress / test"
  * }}}
  */
class AxiTopStressSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXI top stress"

  case class StressCase(
      read: Int,
      write: Int,
      reverse: Boolean,
      lineBytes: Int,
      lineCount: Int,
      readBurst: Int = 0,
      writeBurst: Int = 16
  )

  private val cases: Seq[StressCase] = Seq(
    // Equal widths.
    StressCase(32, 32, reverse = false, lineBytes = 256, lineCount = 8),
    StressCase(64, 64, reverse = false, lineBytes = 1024, lineCount = 8),
    StressCase(64, 64, reverse = true, lineBytes = 1024, lineCount = 8),
    // Widening.
    StressCase(32, 64, reverse = false, lineBytes = 256, lineCount = 8),
    StressCase(64, 128, reverse = false, lineBytes = 1024, lineCount = 8),
    StressCase(64, 512, reverse = false, lineBytes = 4096, lineCount = 4, readBurst = 256),
    // Narrowing.
    StressCase(64, 32, reverse = false, lineBytes = 256, lineCount = 8),
    StressCase(128, 64, reverse = false, lineBytes = 1024, lineCount = 8),
    StressCase(512, 64, reverse = false, lineBytes = 4096, lineCount = 4),
    // Reverse (physical ports swapped) - source is io.write.
    StressCase(64, 128, reverse = true, lineBytes = 1024, lineCount = 8),
    StressCase(32, 512, reverse = true, lineBytes = 4096, lineCount = 4),
    // Long bursts.
    StressCase(64, 128, reverse = false, lineBytes = 4096, lineCount = 4, readBurst = 256, writeBurst = 256),
    StressCase(128, 64, reverse = false, lineBytes = 4096, lineCount = 4, readBurst = 256, writeBurst = 256),
    StressCase(64, 128, reverse = true, lineBytes = 4096, lineCount = 4, readBurst = 256, writeBurst = 256)
  )

  private val only: Option[Set[Int]] =
    sys.env.get("STRESS_CASES").map(_.split(",").iterator.map(_.trim.toInt).toSet)

  cases.zipWithIndex.foreach {
    case (c, i) if only.forall(_.contains(i)) =>
      it should f"case $i%02d: ${c.read}->${c.write}%3d reverse=${c.reverse}%-5s " +
        f"line=${c.lineBytes}x${c.lineCount} burst=${c.readBurst}/${c.writeBurst}" in {
        val cfg = new DMAConfig(
          "AXI_AXIL_AXI",
          sourceWidth = c.read,
          destWidth = c.write,
          readMaxBurst = c.readBurst,
          writeMaxBurst = c.writeBurst
        )
        test(new DMATop()(cfg))
          .runPeekPoke(dut => new AxiTopStressTest(dut, c.reverse, c.lineBytes, c.lineCount, seed = i)(cfg))
      }
    case _ =>
  }
}
