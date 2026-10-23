<template>
  <div>
    <el-tabs type="border-card">
      <el-tab-pane label="秒" v-if="shouldHide('second')">
        <CrontabSecond
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronsecond"
        />
      </el-tab-pane>

      <el-tab-pane label="分钟" v-if="shouldHide('min')">
        <CrontabMin
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronmin"
        />
      </el-tab-pane>

      <el-tab-pane label="小时" v-if="shouldHide('hour')">
        <CrontabHour
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronhour"
        />
      </el-tab-pane>

      <el-tab-pane label="日" v-if="shouldHide('day')">
        <CrontabDay
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronday"
        />
      </el-tab-pane>

      <el-tab-pane label="月" v-if="shouldHide('month')">
        <CrontabMonth
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronmonth"
        />
      </el-tab-pane>

      <el-tab-pane label="周" v-if="shouldHide('week')">
        <CrontabWeek
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronweek"
        />
      </el-tab-pane>

      <el-tab-pane label="年" v-if="shouldHide('year')">
        <CrontabYear
          @update="updateCrontabValue"
          :check="checkNumber"
          :cron="crontabValueObj"
          ref="cronyear"
        />
      </el-tab-pane>
    </el-tabs>

    <div class="popup-main">
      <div class="popup-result">
        <p class="title">时间表达式</p>
        <table>
          <thead>
            <tr>
              <th v-for="item of tabTitles" width="40" :key="item">{{item}}</th>
              <th>Cron 表达式</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td>
                <span>{{crontabValueObj.second}}</span>
              </td>
              <td>
                <span>{{crontabValueObj.min}}</span>
              </td>
              <td>
                <span>{{crontabValueObj.hour}}</span>
              </td>
              <td>
                <span>{{crontabValueObj.day}}</span>
              </td>
              <td>
                <span>{{crontabValueObj.month}}</span>
              </td>
              <td>
                <span>{{crontabValueObj.week}}</span>
              </td>
              <td>
                <span>{{crontabValueObj.year}}</span>
              </td>
              <td>
                <span>{{crontabValueString}}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <CrontabResult :ex="crontabValueString"></CrontabResult>

      <div class="pop_btn">
        <el-button size="small" type="primary" @click="submitFill">确定</el-button>
        <el-button size="small" type="warning" @click="clearCron">重置</el-button>
        <el-button size="small" @click="hidePopup">取消</el-button>
      </div>
    </div>
  </div>
</template>

<script>
import { defineComponent, ref, reactive, computed, watch, onMounted } from 'vue'
import CrontabSecond from "./second.vue"
import CrontabMin from "./min.vue"
import CrontabHour from "./hour.vue"
import CrontabDay from "./day.vue"
import CrontabMonth from "./month.vue"
import CrontabWeek from "./week.vue"
import CrontabYear from "./year.vue"
import CrontabResult from "./result.vue"

export default defineComponent({
  name: "crontab",
  components: {
    CrontabSecond,
    CrontabMin,
    CrontabHour,
    CrontabDay,
    CrontabMonth,
    CrontabWeek,
    CrontabYear,
    CrontabResult,
  },
  props: {
    expression: {
      type: String,
      default: ''
    },
    hideComponent: {
      type: Array,
      default: () => []
    }
  },
  emits: ['hide', 'fill'],
  setup(props, { emit, expose }) {
    const tabTitles = ["秒", "分钟", "小时", "日", "月", "周", "年"]
    
    const crontabValueObj = reactive({
      second: "*",
      min: "*",
      hour: "*",
      day: "*",
      month: "*",
      week: "?",
      year: "",
    })

    const refs = {
      cronsecond: ref(null),
      cronmin: ref(null),
      cronhour: ref(null),
      cronday: ref(null),
      cronmonth: ref(null),
      cronweek: ref(null),
      cronyear: ref(null),
    }

    const shouldHide = (key) => {
      if (props.hideComponent && props.hideComponent.includes(key)) return false
      return true
    }

    const checkNumber = (value, minLimit, maxLimit) => {
      value = Math.floor(value)
      if (value < minLimit) {
        value = minLimit
      } else if (value > maxLimit) {
        value = maxLimit
      }
      return value
    }

    const crontabValueString = computed(() => {
      let obj = crontabValueObj
      let str =
        obj.second +
        " " +
        obj.min +
        " " +
        obj.hour +
        " " +
        obj.day +
        " " +
        obj.month +
        " " +
        obj.week +
        (obj.year == "" ? "" : " " + obj.year)
      return str
    })

    const changeRadio = (name, value) => {
      let arr = ["second", "min", "hour", "month"]
      let refName = "cron" + name
      let insValue

      const refEl = refs[refName]?.value
      if (!refEl) return

      if (arr.includes(name)) {
        if (value === "*") {
          insValue = 1
        } else if (value.indexOf("-") > -1) {
          let indexArr = value.split("-")
          isNaN(indexArr[0])
            ? (refEl.cycle01 = 0)
            : (refEl.cycle01 = indexArr[0])
          refEl.cycle02 = indexArr[1]
          insValue = 2
        } else if (value.indexOf("/") > -1) {
          let indexArr = value.split("/")
          isNaN(indexArr[0])
            ? (refEl.average01 = 0)
            : (refEl.average01 = indexArr[0])
          refEl.average02 = indexArr[1]
          insValue = 3
        } else {
          insValue = 4
          refEl.checkboxList = value.split(",")
        }
      } else if (name == "day") {
        if (value === "*") {
          insValue = 1
        } else if (value == "?") {
          insValue = 2
        } else if (value.indexOf("-") > -1) {
          let indexArr = value.split("-")
          isNaN(indexArr[0])
            ? (refEl.cycle01 = 0)
            : (refEl.cycle01 = indexArr[0])
          refEl.cycle02 = indexArr[1]
          insValue = 3
        } else if (value.indexOf("/") > -1) {
          let indexArr = value.split("/")
          isNaN(indexArr[0])
            ? (refEl.average01 = 0)
            : (refEl.average01 = indexArr[0])
          refEl.average02 = indexArr[1]
          insValue = 4
        } else if (value.indexOf("W") > -1) {
          let indexArr = value.split("W")
          isNaN(indexArr[0])
            ? (refEl.workday = 0)
            : (refEl.workday = indexArr[0])
          insValue = 5
        } else if (value === "L") {
          insValue = 6
        } else {
          refEl.checkboxList = value.split(",")
          insValue = 7
        }
      } else if (name == "week") {
        if (value === "*") {
          insValue = 1
        } else if (value == "?") {
          insValue = 2
        } else if (value.indexOf("-") > -1) {
          let indexArr = value.split("-")
          isNaN(indexArr[0])
            ? (refEl.cycle01 = 0)
            : (refEl.cycle01 = indexArr[0])
          refEl.cycle02 = indexArr[1]
          insValue = 3
        } else if (value.indexOf("#") > -1) {
          let indexArr = value.split("#")
          isNaN(indexArr[0])
            ? (refEl.average01 = 1)
            : (refEl.average01 = indexArr[0])
          refEl.average02 = indexArr[1]
          insValue = 4
        } else if (value.indexOf("L") > -1) {
          let indexArr = value.split("L")
          isNaN(indexArr[0])
            ? (refEl.weekday = 1)
            : (refEl.weekday = indexArr[0])
          insValue = 5
        } else {
          refEl.checkboxList = value.split(",")
          insValue = 6
        }
      } else if (name == "year") {
        if (value == "") {
          insValue = 1
        } else if (value == "*") {
          insValue = 2
        } else if (value.indexOf("-") > -1) {
          insValue = 3
        } else if (value.indexOf("/") > -1) {
          insValue = 4
        } else {
          refEl.checkboxList = value.split(",")
          insValue = 5
        }
      }
      refEl.radioValue = insValue
    }

    const resolveExp = () => {
      if (props.expression) {
        let arr = props.expression.split(" ")
        if (arr.length >= 6) {
          let obj = {
            second: arr[0],
            min: arr[1],
            hour: arr[2],
            day: arr[3],
            month: arr[4],
            week: arr[5],
            year: arr[6] ? arr[6] : "",
          }
          Object.assign(crontabValueObj, obj)
          for (let i in obj) {
            if (obj[i]) changeRadio(i, obj[i])
          }
        }
      } else {
        clearCron()
      }
    }

    const updateCrontabValue = (name, value, from) => {
      crontabValueObj[name] = value
      if (from && from !== name) {
        changeRadio(name, value)
      }
    }

    const hidePopup = () => {
      emit("hide")
    }

    const submitFill = () => {
      emit("fill", crontabValueString.value)
      hidePopup()
    }

    const clearCron = () => {
      Object.assign(crontabValueObj, {
        second: "*",
        min: "*",
        hour: "*",
        day: "*",
        month: "*",
        week: "?",
        year: "",
      })
      for (let j in crontabValueObj) {
        changeRadio(j, crontabValueObj[j])
      }
    }

    watch(() => props.expression, () => {
      resolveExp()
    })

    onMounted(() => {
      resolveExp()
    })

    // 暴露 refs 给模板使用
    expose(refs)

    return {
      tabTitles,
      crontabValueObj,
      crontabValueString,
      shouldHide,
      checkNumber,
      updateCrontabValue,
      hidePopup,
      submitFill,
      clearCron,
      changeRadio,
      ...refs
    }
  }
})
</script>

<style scoped>
.pop_btn {
  text-align: center;
  margin-top: 20px;
}
.popup-main {
  position: relative;
  margin: 10px auto;
  background: #fff;
  border-radius: 5px;
  font-size: 12px;
  overflow: hidden;
}
.popup-title {
  overflow: hidden;
  line-height: 34px;
  padding-top: 6px;
  background: #f2f2f2;
}
.popup-result {
  box-sizing: border-box;
  line-height: 24px;
  margin: 25px auto;
  padding: 15px 10px 10px;
  border: 1px solid #ccc;
  position: relative;
}
.popup-result .title {
  position: absolute;
  top: -28px;
  left: 50%;
  width: 140px;
  font-size: 14px;
  margin-left: -70px;
  text-align: center;
  line-height: 30px;
  background: #fff;
}
.popup-result table {
  text-align: center;
  width: 100%;
  margin: 0 auto;
}
.popup-result table span {
  display: block;
  width: 100%;
  font-family: arial;
  line-height: 30px;
  height: 30px;
  white-space: nowrap;
  overflow: hidden;
  border: 1px solid #e8e8e8;
}
.popup-result-scroll {
  font-size: 12px;
  line-height: 24px;
  height: 10em;
  overflow-y: auto;
}
</style>