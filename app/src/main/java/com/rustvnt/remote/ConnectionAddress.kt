package com.rustvnt.remote

/** The desktop's combined host[:port] field, including bracketed IPv6. No DNS on the UI thread. */
object ConnectionAddress {
    data class Address(val host: String, val port: Int)
    fun parse(value: String): Address {
        val raw=value.trim()
        require(raw.isNotEmpty() && raw.none {it.isWhitespace() || it=='/' || it=='\\'}) {"请输入 IP 或域名，可附加端口"}
        var host=raw;var port=29875
        if(raw.startsWith('[')) {
            val end=raw.indexOf(']');require(end>1) {"IPv6 地址格式无效"}
            host=raw.substring(1,end);val suffix=raw.substring(end+1)
            if(suffix.isNotEmpty()) {require(suffix.startsWith(':')) {"地址格式无效"};port=suffix.substring(1).toIntOrNull()?:0}
        } else if(raw.count {it==':'}==1) {
            host=raw.substringBefore(':');port=raw.substringAfter(':').toIntOrNull()?:0
        }
        require(host.isNotEmpty() && '[' !in host && ']' !in host) {"地址格式无效"}
        require(port in 1..65535) {"端口须为 1–65535"}
        return Address(host,port)
    }
    fun format(host:String,port:Int=29875):String {
        val address=if(':' in host)"[$host]" else host
        return if(port==29875) address else "$address:$port"
    }
}
