import sys
sys.path.insert(0, '.')
from midealocal.devices.ac.message import *
from midealocal.devices.ac import message as m
from midealocal.crc8 import crc8_854_table
from midealocal.security import LocalSecurity, CloudSecurity, MideaAirSecurity
from midealocal.message import MessageQueryAppliance
from midealocal.const import DeviceType
import hashlib
print("crc_table_md5", hashlib.md5(bytes(crc8_854_table)).hexdigest())
MessageACBase._message_serial = 0
q = MessageQuery(3); print("query41", q.serialize().hex())
s = MessageGeneralSet(3)
s.power=True; s.mode=2; s.target_temperature=23.5; s.fan_speed=60; s.swing_vertical=True; s.eco_mode=True; s.prompt_tone=True
print("set40", s.serialize().hex())
n = MessageNewProtocolQuery(3); print("queryB1", n.serialize().hex())
ns = MessageNewProtocolSet(3); ns.prompt_tone=True; ns.breezeless=True; ns.wind_ud_angle=50
print("setB0", ns.serialize().hex())
print("capsB5", MessageCapabilitiesQuery(3).serialize().hex())
print("caps2B5", MessageCapabilitiesAdditionalQuery(3).serialize().hex())
print("power44", MessagePowerQuery(3).serialize().hex())
print("humidity45", MessageHumidityQuery(3).serialize().hex())
t = MessageToggleDisplay(3); t.prompt_tone=True; print("toggle", t.serialize().hex())
print("appliance", MessageQueryAppliance(DeviceType.AC).serialize().hex())
ls = LocalSecurity()
print("aeskey", ls.aes_key.hex()); print("salt", ls.salt.hex())
print("enc32", ls.encode32_data(b"hello").hex())
print("aesecb", ls.aes_encrypt(bytes.fromhex("aa20ac0000")).hex())
print("udp1", CloudSecurity.get_udp_id(151732605161920, 1))
print("udp2", CloudSecurity.get_udp_id(151732605161920, 2))
print("devid", CloudSecurity.get_deviceid("user@example.com"))
sec = MideaAirSecurity("3742e9e5842d4ad59c2db887e12449f9")
print("airsign", sec.sign("https://mapp.appsmb.com/v1/user/login/id/get", {"src":"1017","loginAccount":"a@b.c","appId":"1017","format":"2"}, ""))
print("pw", sec.encrypt_password("loginid123", "secret"))
from midealocal.security import MSmartCloudSecurity
from midealocal.cloud import SUPPORTED_CLOUDS
sh = SUPPORTED_CLOUDS["SmartHome"]
print("sh_iot", sh["iot_key"], "sh_hmac", sh["hmac_key"])
ms = MSmartCloudSecurity(sh["app_key"], sh["iot_key"], sh["hmac_key"])
print("mssign", ms.sign("", '{"a": 1}', "1700000000"))
print("msiam", ms.encrypt_iam_password("loginid123","secret"))
# response parsing vector: build a C0 frame
body = bytearray(25); body[0]=0xC0; body[1]=0x01; body[2]=(2<<5)|(7)|0x10; body[3]=40; body[7]=0x3C; body[9]=0x10; body[11]=50+2*24; body[12]=50+2*18; body[15]=0x35
hdr = bytearray([0xAA, 10+len(body)+1, 0xAC,0,0,0,0,0,3,0x03])
frame = hdr+body
frame.append((~sum(frame[1:])+1)&0xFF)
print("c0frame", frame.hex())
r = MessageACResponse(frame)
print("c0parsed", r.power, r.mode, r.target_temperature, r.fan_speed, r.swing_vertical, r.swing_horizontal, r.eco_mode, r.indoor_temperature, r.outdoor_temperature)
# 8370
ls2 = LocalSecurity(); ls2._tcp_key = bytes(range(32))
enc = ls2.encode_8370(bytes(range(56)), 6)
ls3 = LocalSecurity(); ls3._tcp_key = bytes(range(32))
print("8370len", len(enc), "roundtrip", ls3.decode_8370(enc)[0][0] == bytes(range(56)))
