from django.urls import path

from . import views

app_name = "sms"

urlpatterns = [
    path("staff/logs/", views.log_list, name="logs"),
    path("staff/logs/<int:log_id>/resend/", views.resend, name="resend"),
]
