"""직원이 올린 상품 사진 처리: 최대 1600px JPEG 로 줄이고 메타데이터(위치 정보 등)를 지운다."""

import io
import uuid

from django.core.files.base import ContentFile
from PIL import Image, ImageOps

MAX_SIDE = 1600
MAX_BYTES = 5 * 1024 * 1024


class InvalidImage(ValueError):
    pass


def process_upload(uploaded) -> ContentFile:
    if uploaded.size > MAX_BYTES:
        raise InvalidImage("사진은 5MB 이하로 올려 주세요.")
    try:
        im = Image.open(uploaded)
        im = ImageOps.exif_transpose(im)  # 휴대폰 사진 회전 보정
        im = im.convert("RGB")
    except Exception as e:
        raise InvalidImage("사진 파일을 읽을 수 없습니다. JPG·PNG 파일을 올려 주세요.") from e
    im.thumbnail((MAX_SIDE, MAX_SIDE))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=82, optimize=True, progressive=True)
    return ContentFile(buf.getvalue(), name=f"{uuid.uuid4().hex[:12]}.jpg")
