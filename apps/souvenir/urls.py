from django.urls import path

from . import views

app_name = "souvenir"

urlpatterns = [
    path("staff/stock/", views.stock_page, name="stock"),
    path("staff/souvenirs/", views.manage_page, name="manage"),
    path("staff/souvenirs/new/", views.register_page, name="register"),
    path("staff/souvenirs/<int:souvenir_id>/edit/", views.edit_page, name="edit"),
    path("staff/souvenirs/<int:souvenir_id>/delete/", views.delete_souvenir, name="delete"),
    path("staff/souvenirs/issues/", views.issue_list, name="issues"),
    path("staff/stock/list/", views.souvenir_list, name="list"),
    path("staff/stock/prepare/", views.prepare_receipt, name="prepare"),
    path("staff/stock/record/", views.record_receipt, name="record"),
]
