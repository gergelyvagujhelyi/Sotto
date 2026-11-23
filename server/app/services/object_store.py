"""The object store behind encrypted attachments (ADR-29): presigned URLs only.

The relay never carries file bytes. It signs short-lived URLs (AWS Signature
Version 4, query-string form) that let a phone PUT or GET one object directly,
and itself only sends HEAD (to check an upload's size) and DELETE. Any
S3-compatible store works; the author's relay uses Tigris, whose free egress
and card-less free tier keep it free.
"""

from __future__ import annotations

import datetime as _dt
import hashlib
import hmac
import re
from xml.sax.saxutils import escape
from dataclasses import dataclass
from typing import Optional
from urllib.parse import quote, urlsplit

import httpx

_SAFE = "-_.~"


def _q(value: str) -> str:
    return quote(value, safe=_SAFE)


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


@dataclass(frozen=True)
class ObjectStore:
    """One bucket: `endpoint` like https://t3.storage.dev, addressed virtual-host style."""

    endpoint: str
    bucket: str
    access_key_id: str
    secret: str
    region: str = "auto"

    @property
    def host(self) -> str:
        return f"{self.bucket}.{urlsplit(self.endpoint).netloc}"

    @property
    def scheme(self) -> str:
        return urlsplit(self.endpoint).scheme or "https"

    def presign(
        self,
        method: str,
        key: str,
        expires: int,
        signed_headers: Optional[dict] = None,
        now: Optional[_dt.datetime] = None,
        query: Optional[dict] = None,
    ) -> str:
        """A URL that lets its holder send `method` on `key` for `expires` seconds.

        `signed_headers` (e.g. content-length) become part of the signature: the
        request must carry exactly those values, or the store refuses it.
        """
        now = now or _dt.datetime.now(_dt.timezone.utc)
        amz_date = now.strftime("%Y%m%dT%H%M%SZ")
        datestamp = now.strftime("%Y%m%d")
        scope = f"{datestamp}/{self.region}/s3/aws4_request"
        headers = {"host": self.host}
        for name, value in (signed_headers or {}).items():
            headers[name.lower()] = str(value).strip()
        names = ";".join(sorted(headers))
        query = dict(query or {})
        query.update({
            "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
            "X-Amz-Credential": f"{self.access_key_id}/{scope}",
            "X-Amz-Date": amz_date,
            "X-Amz-Expires": str(expires),
            "X-Amz-SignedHeaders": names,
        })
        canonical_query = "&".join(f"{_q(k)}={_q(v)}" for k, v in sorted(query.items()))
        path = "/" + quote(key, safe="/" + _SAFE)
        canonical_headers = "".join(f"{k}:{headers[k]}\n" for k in sorted(headers))
        canonical_request = "\n".join(
            [method, path, canonical_query, canonical_headers, names, "UNSIGNED-PAYLOAD"]
        )
        string_to_sign = "\n".join([
            "AWS4-HMAC-SHA256",
            amz_date,
            scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ])
        k = _hmac(("AWS4" + self.secret).encode("utf-8"), datestamp)
        k = _hmac(k, self.region)
        k = _hmac(k, "s3")
        k = _hmac(k, "aws4_request")
        signature = hmac.new(k, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
        return f"{self.scheme}://{self.host}{path}?{canonical_query}&X-Amz-Signature={signature}"

    async def size_of(self, client: httpx.AsyncClient, key: str) -> Optional[int]:
        """The stored object's size in bytes; None when it isn't there."""
        response = await client.head(self.presign("HEAD", key, expires=60))
        if response.status_code == 404:
            return None
        response.raise_for_status()
        return int(response.headers.get("content-length", "-1"))

    async def create_multipart(self, client: httpx.AsyncClient, key: str) -> str:
        """Starts a multipart upload of `key`; returns its upload id."""
        response = await client.post(self.presign("POST", key, expires=60, query={"uploads": ""}))
        response.raise_for_status()
        match = re.search(r"<UploadId>([^<]+)</UploadId>", response.text)
        if not match:
            raise httpx.HTTPError("No UploadId in the store's answer")
        return match.group(1)

    def presign_part(self, key: str, upload_id: str, number: int, size: int, expires: int) -> str:
        """A URL for uploading part `number` (from 1) of exactly `size` bytes."""
        return self.presign(
            "PUT", key, expires, signed_headers={"content-length": size},
            query={"partNumber": str(number), "uploadId": upload_id},
        )

    async def complete_multipart(self, client: httpx.AsyncClient, key: str, upload_id: str, etags: list[str]) -> None:
        """Joins the uploaded parts, in order, into the object."""
        body = "<CompleteMultipartUpload>" + "".join(
            f"<Part><PartNumber>{i}</PartNumber><ETag>{escape(tag)}</ETag></Part>"
            for i, tag in enumerate(etags, start=1)
        ) + "</CompleteMultipartUpload>"
        response = await client.post(
            self.presign("POST", key, expires=60, query={"uploadId": upload_id}),
            content=body.encode(),
            headers={"content-type": "application/xml"},
        )
        response.raise_for_status()
        # S3 can answer 200 with an error in the body.
        if "<Error>" in response.text:
            raise httpx.HTTPError("The store refused to join the parts")

    async def abort_multipart(self, client: httpx.AsyncClient, key: str, upload_id: str) -> None:
        """Throws an unfinished multipart upload's parts away (a missing one counts as gone)."""
        response = await client.delete(self.presign("DELETE", key, expires=60, query={"uploadId": upload_id}))
        if response.status_code not in (200, 204, 404):
            response.raise_for_status()

    async def delete(self, client: httpx.AsyncClient, key: str) -> None:
        """Removes the object (a missing one counts as removed)."""
        response = await client.delete(self.presign("DELETE", key, expires=60))
        if response.status_code not in (200, 204, 404):
            response.raise_for_status()


def configured_store() -> Optional[ObjectStore]:
    """The store from the S3_* settings, or None when this relay offers no attachments."""
    from app import config

    if not (config.S3_ENDPOINT and config.S3_BUCKET and config.S3_ACCESS_KEY_ID and config.S3_SECRET):
        return None
    return ObjectStore(
        endpoint=config.S3_ENDPOINT.rstrip("/"),
        bucket=config.S3_BUCKET,
        access_key_id=config.S3_ACCESS_KEY_ID,
        secret=config.S3_SECRET,
        region=config.S3_REGION,
    )
