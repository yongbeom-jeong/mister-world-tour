"""설계(01장 5.1)의 테마별 기념품 4종을 미리 등록한다. 재고는 0에서 시작해 입고(UC8)로 늘린다."""

from django.db import migrations

SOUVENIRS = [
    ("커플 기념티", "HONEYMOON"),
    ("건강 인삼", "FILIAL"),
    ("골프공", "GOLF"),
    ("기념품 스카프", "TREKKING"),
]


def create(apps, schema_editor):
    Souvenir = apps.get_model("souvenir", "Souvenir")
    for name, theme in SOUVENIRS:
        Souvenir.objects.get_or_create(name=name, defaults={"theme": theme, "stock_quantity": 0})


def remove(apps, schema_editor):
    apps.get_model("souvenir", "Souvenir").objects.filter(name__in=[n for n, _ in SOUVENIRS]).delete()


class Migration(migrations.Migration):
    dependencies = [("souvenir", "0001_initial")]
    operations = [migrations.RunPython(create, remove)]
