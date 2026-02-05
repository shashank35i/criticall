package com.simats.criticall.roles.doctor

sealed class DoctorHomeState {
    data object Loading : DoctorHomeState()
    data class Ready(val data: DoctorHomeData) : DoctorHomeState()
    data object Error : DoctorHomeState()
}
