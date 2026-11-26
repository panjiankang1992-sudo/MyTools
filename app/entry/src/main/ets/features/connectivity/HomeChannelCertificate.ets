import socket from '@ohos.net.socket';
import cert from '@ohos.security.cert';

interface CertificateField {
  tag: number;
  start: number;
  end: number;
}

/** 在系统完成证书链校验后，按叶证书中带类型的 SAN 核对本业务主机。 */
export class HomeChannelCertificate {
  /** 认证头发送前验证远端叶证书，缺少 SAN 或格式异常一律拒绝。 */
  static async verify(raw: socket.X509CertRawData, host: string): Promise<void> {
    try {
      if (!raw || !raw.data || raw.data.length === 0 || raw.data.length > 65536) throw new Error();
      const leaf = await cert.createX509Cert(raw);
      const encoded = await leaf.getEncoded();
      if (encoded.encodingFormat !== cert.EncodingFormat.FORMAT_DER ||
        !HomeChannelCertificate.matches(encoded.data, host)) throw new Error();
    } catch (_) {
      throw new Error('Channel certificate hostname rejected');
    }
  }

  /** 从完整 DER 叶证书读取 SAN；不使用会丢失名称类型和零字节的字符串接口。 */
  static matches(der: Uint8Array, host: string): boolean {
    try {
      if (!der || der.length === 0 || der.length > 65536 || !HomeChannelCertificate.dns(host)) return false;
      const root = HomeChannelCertificate.field(der, 0, der.length);
      if (root.tag !== 0x30 || root.end !== der.length) return false;
      const tbs = HomeChannelCertificate.field(der, root.start, root.end);
      if (tbs.tag !== 0x30) return false;
      let extensions: CertificateField | undefined;
      let cursor = tbs.start;
      let fields = 0;
      while (cursor < tbs.end) {
        if (++fields > 16) return false;
        const value = HomeChannelCertificate.field(der, cursor, tbs.end);
        if (value.tag === 0xa3) {
          if (extensions !== undefined) return false;
          extensions = value;
        }
        cursor = value.end;
      }
      if (extensions === undefined) return false;
      const sequence = HomeChannelCertificate.field(der, extensions.start, extensions.end);
      if (sequence.tag !== 0x30 || sequence.end !== extensions.end) return false;
      cursor = sequence.start;
      let san: CertificateField | undefined;
      let count = 0;
      while (cursor < sequence.end) {
        if (++count > 128) return false;
        const extension = HomeChannelCertificate.field(der, cursor, sequence.end);
        if (extension.tag !== 0x30) return false;
        const oid = HomeChannelCertificate.field(der, extension.start, extension.end);
        if (oid.tag !== 0x06 || oid.end === extension.end) return false;
        let value = HomeChannelCertificate.field(der, oid.end, extension.end);
        if (value.tag === 0x01) {
          if (value.end - value.start !== 1 || (der[value.start] !== 0 && der[value.start] !== 255)) return false;
          value = HomeChannelCertificate.field(der, value.end, extension.end);
        }
        if (value.tag !== 0x04 || value.end !== extension.end) return false;
        if (oid.end - oid.start === 3 && der[oid.start] === 0x55 &&
          der[oid.start + 1] === 0x1d && der[oid.start + 2] === 0x11) {
          if (san !== undefined) return false;
          san = value;
        }
        cursor = extension.end;
      }
      if (san === undefined) return false;
      return HomeChannelCertificate.names(der, san, host.toLowerCase());
    } catch (_) {
      return false;
    }
  }

  private static field(data: Uint8Array, cursor: number, boundary: number): CertificateField {
    if (cursor < 0 || cursor + 2 > boundary || boundary > data.length) throw new Error();
    const tag = data[cursor++];
    if ((tag & 31) === 31) throw new Error();
    let length = data[cursor++];
    if (length >= 128) {
      const octets = length & 127;
      if (octets === 0 || octets > 3 || cursor + octets > boundary || data[cursor] === 0) throw new Error();
      length = 0;
      for (let i = 0; i < octets; i++) length = length * 256 + data[cursor++];
      if (length < 128) throw new Error();
    }
    if (length > boundary - cursor) throw new Error();
    return { tag: tag, start: cursor, end: cursor + length };
  }

  private static names(der: Uint8Array, san: CertificateField, host: string): boolean {
    const sequence = HomeChannelCertificate.field(der, san.start, san.end);
    if (sequence.tag !== 0x30 || sequence.end !== san.end) return false;
    const ip = HomeChannelCertificate.ipv4(host);
    if (/^[0-9.]+$/.test(host) && ip.length !== 4) return false;
    let cursor = sequence.start;
    let count = 0;
    let matched = false;
    while (cursor < sequence.end) {
      if (++count > 128) return false;
      const name = HomeChannelCertificate.field(der, cursor, sequence.end);
      if ((name.tag & 0xc0) !== 0x80 || (name.tag & 31) > 8) return false;
      if (name.tag === 0x87) {
        const length = name.end - name.start;
        if (length !== 4 && length !== 16) return false;
        if (ip.length === 4 && length === 4 && ip.every((value: number, i: number): boolean =>
          value === der[name.start + i])) matched = true;
      } else if (name.tag === 0x82) {
        if (name.end - name.start > 253 || name.end === name.start) return false;
        let dns = '';
        for (let i = name.start; i < name.end; i++) {
          if (der[i] < 33 || der[i] > 126) return false;
          dns += String.fromCharCode(der[i]);
        }
        dns = dns.toLowerCase();
        const wildcard = dns.startsWith('*.');
        const suffix = wildcard ? dns.substring(2) : dns;
        if (!HomeChannelCertificate.dns(suffix)) return false;
        if (ip.length === 0 && (dns === host || (wildcard && suffix.includes('.') &&
          host.substring(host.indexOf('.') + 1) === suffix && host.includes('.')))) matched = true;
      }
      cursor = name.end;
    }
    return matched;
  }

  private static dns(value: string): boolean {
    return value.length > 0 && value.length <= 253 && value.split('.').every((label: string): boolean =>
      label.length > 0 && label.length <= 63 && /^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?$/i.test(label));
  }

  private static ipv4(value: string): number[] {
    const parts = value.split('.');
    if (parts.length !== 4 || !parts.every((part: string): boolean =>
      /^(0|[1-9][0-9]{0,2})$/.test(part) && Number(part) <= 255)) return [];
    return parts.map((part: string): number => Number(part));
  }
}
