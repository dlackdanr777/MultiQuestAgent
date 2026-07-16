package tdg.oculuswirelessadb.rtsp

import java.nio.ByteBuffer

object H264Util {

    /**
     * ByteBuffer에서 데이터를 읽어 NAL 유닛 리스트로 분리합니다.
     */
    fun splitNalUnitsFromBuffer(buffer: ByteBuffer): List<ByteArray> {
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return splitNalUnits(bytes)
    }

    /**
     * Annex-B 형식(00 00 01 또는 00 00 00 01)의 데이터를 스캔하여
     * 시작 코드를 제거한 순수 NAL 유닛 리스트를 반환합니다.
     */
    fun splitNalUnits(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        if (data.isEmpty()) return nals

        var i = 0
        val size = data.size

        while (i < size - 2) {
            // 시작 코드 00 00 01 확인
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                val start = i + 3
                val nextStart = findNextStartCode(data, start)
                nals.add(data.copyOfRange(start, nextStart))
                i = nextStart
            }
            // 시작 코드 00 00 00 01 확인
            else if (i < size - 3 && data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                val start = i + 4
                val nextStart = findNextStartCode(data, start)
                nals.add(data.copyOfRange(start, nextStart))
                i = nextStart
            } else {
                i++
            }
        }

        // 빈 배열 제외 및 유효성 검사
        return nals.filter { it.isNotEmpty() }
    }

    /**
     * NAL 유닛의 타입을 반환합니다 (SPS: 7, PPS: 8, IDR: 5 등).
     */
    fun nalType(nal: ByteArray): Int {
        return if (nal.isNotEmpty()) {
            nal[0].toInt() and 0x1F
        } else {
            -1
        }
    }

    /**
     * 다음 시작 코드의 위치를 찾습니다.
     */
    private fun findNextStartCode(data: ByteArray, start: Int): Int {
        val size = data.size
        for (i in start until size - 2) {
            // 00 00 01 패턴 탐색
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                // 바로 앞이 00인지 확인하여 00 00 00 01 케이스 처리
                return if (i > 0 && data[i - 1].toInt() == 0) i - 1 else i
            }
        }
        return size
    }
}