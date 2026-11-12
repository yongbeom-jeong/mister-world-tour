from django.urls import path

from . import views

app_name = "speech"

urlpatterns = [
    path("recognize/", views.recognize, name="recognize"),
    path("failure/", views.browser_failure, name="failure"),
]
