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
  * Checks that a source buffer aligned only to the narrow side works when the
  * wider destination is aligned to its own bus width.
  */
class AxiTopSourceAlignSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXI top wrapper source-buffer alignment"

  private val cfg = new DMAConfig("AXI_AXIL_AXI", sourceWidth = 32, destWidth = 512)

  it should "copy a narrow-side-aligned source to a wide-side-aligned address" in {
    test(new DMATop()(cfg)).runPeekPoke(new AxiTopSourceAlignTest(_)(cfg))
  }
}
