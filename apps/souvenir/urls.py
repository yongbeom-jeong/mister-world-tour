from django.urls import path

from . import views

app_name = "souvenir"

urlpatterns = [
    path("staff/stock/", views.stock_page, name="stock"),
    path("staff/souvenirs/new/", views.register_page, name="register"),
    path("staff/stock/list/", views.souvenir_list, name="list"),
    path("staff/stock/prepare/", views.prepare_receipt, name="prepare"),
    path("staff/stock/record/", views.record_receipt, name="record"),
]
