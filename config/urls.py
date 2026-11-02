from django.contrib import admin
from django.urls import include, path
from . import views

urlpatterns = [
    path("", views.home, name="home"),
    path("admin/", admin.site.urls),
    path("members/", include("apps.members.urls")),
    path("travel/", include("apps.travel.urls")),
    path("souvenir/", include("apps.souvenir.urls")),
    path("speech/", include("apps.speech.urls")),
    path("sms/", include("apps.sms.urls")),
]
