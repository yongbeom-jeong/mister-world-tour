from django.urls import path

from . import customer_views, views

app_name = "travel"

urlpatterns = [
    # 고객
    path("history/", views.history_partial, name="history"),
    path("products/", customer_views.product_browse, name="products"),
    path("products/<int:product_id>/", customer_views.product_detail, name="product_detail"),
    path("products/<int:product_id>/configure/", customer_views.configure, name="configure"),
    path("products/<int:product_id>/apply/", customer_views.start_application, name="start_application"),
    path("apply/", customer_views.apply, name="apply"),
    path("applications/", customer_views.my_applications, name="my_applications"),
    path("applications/<int:application_id>/", customer_views.application_detail, name="application_detail"),
    path("applications/<int:application_id>/cancel/", customer_views.cancel_application, name="cancel_application"),
    # 직원: 상품 등록·관리
    path("staff/products/new/", views.product_register, name="product_register"),
    path("staff/products/", views.product_manage, name="product_manage"),
    path("staff/products/<int:product_id>/", views.product_manage_detail, name="product_manage_detail"),
    path("staff/products/<int:product_id>/delete/", views.product_delete, name="product_delete"),
    path("staff/default-configuration/", views.default_configuration, name="default_configuration"),
]
