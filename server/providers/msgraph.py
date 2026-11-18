"""Microsoft Graph calendar provider (conceptual wrapper of the existing logic).

NOT wired into main.py yet — main.py still contains its own inline
refresh_microsoft_token()/get_calendar_events() code. This module describes
how that logic maps onto the CalendarProvider interface; the parts that would
duplicate main.py's private helpers raise NotImplementedError so the skeleton
stays honest instead of silently diverging.

Design (docs/CALENDAR-PROVIDERS.md option a):
  * Entra app registration, delegated scopes offline_access + Calendars.Read
    (docs/BACKEND.md section 2).
  * Bootstrap: tools/ms_device_login.py (device-code flow, public client) or
    auth-code flow with a client secret (confidential client).
  * Token refresh: POST refresh_token grant to
    https://login.microsoftonline.com/{tenant}/oauth2/v2.0/token, persisting
    the rotated refresh token to the token file with mode 600. client_secret
    is optional (main.py sends it only when present in ms_app_config.json).
  * Fetch: GET https://graph.microsoft.com/v1.0/me/calendarview with
    startDateTime/endDateTime (UTC) and Prefer: outlook.timezone header.
"""

from datetime import datetime

from .base import CalendarProvider, CalendarProviderError


class MicrosoftGraphProvider(CalendarProvider):
    """Calendar provider backed by Microsoft Graph /me/calendarview."""

    def __init__(self, token_file: str, config_file: str, tenant: str = "common",
                 timezone_name: str = "Europe/Amsterdam"):
        self.token_file = token_file
        self.config_file = config_file
        self.tenant = tenant
        self.timezone_name = timezone_name

    def get_events(self, start: datetime, end: datetime) -> list[dict]:
        # Would: refresh token (main.refresh_microsoft_token), call
        # /me/calendarview, map each Graph event to the normalized dict:
        #   subject      <- event["subject"]
        #   start/end    <- event["start"]["dateTime"] / ["dateTime"]
        #   is_cancelled <- event["isCancelled"] or status == "cancelled"
        #   is_all_day   <- event["isAllDay"]
        #   body         <- event["body"]["content"]
        #   location     <- event["location"]["displayName"]
        #   categories   <- event["categories"]
        raise NotImplementedError(
            "MicrosoftGraphProvider is a design skeleton; main.py still uses "
            "its own inline Microsoft Graph code (see docs/BACKEND.md)."
        )
