"""Hlídá, že v bucketu se zálohami leží čerstvý dump.

Aplikace umí říct, že jí zálohovací job doběhl. To ale není totéž jako že záloha existuje
mimo ten stroj — a přesně to je jediná věc, na které při obnově záleží. Tahle kontrola se
proto ptá S3, ne aplikace, a běží úplně jinde než ona.

Výsledek se zapisuje jako metrika, ne jako poplach: alarm nad metrikou má historii, umí se
vrátit do OK a hlavně umí hlásit i to, že data přestala chodit — tedy že umřela tahle funkce.
"""

import os
import time

import boto3

BUCKET = os.environ["BUCKET"]
PREFIX = os.environ["PREFIX"]
NAMESPACE = os.environ["METRIC_NAMESPACE"]
METRIC = os.environ["METRIC_NAME"]
ENVIRONMENT = os.environ["ENVIRONMENT"]

# Když v bucketu není vůbec nic, nehlásíme nulu ani chybu — hlásíme velké stáří.
# Prázdný bucket je totiž ten nejhorší možný stav, ne ten nejlepší.
NO_BACKUP_AT_ALL_HOURS = 24 * 365

s3 = boto3.client("s3")
cloudwatch = boto3.client("cloudwatch")


def newest_object_time():
    newest = None
    for page in s3.get_paginator("list_objects_v2").paginate(Bucket=BUCKET, Prefix=PREFIX):
        for obj in page.get("Contents", []):
            if newest is None or obj["LastModified"] > newest:
                newest = obj["LastModified"]
    return newest


def handler(event, context):
    newest = newest_object_time()
    age_hours = NO_BACKUP_AT_ALL_HOURS if newest is None else (time.time() - newest.timestamp()) / 3600

    cloudwatch.put_metric_data(
        Namespace=NAMESPACE,
        MetricData=[
            {
                "MetricName": METRIC,
                "Dimensions": [{"Name": "Environment", "Value": ENVIRONMENT}],
                "Value": age_hours,
                "Unit": "None",
            }
        ],
    )

    return {"bucket": BUCKET, "prefix": PREFIX, "age_hours": round(age_hours, 2)}
