package yair.rush

import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.util.Base64

/**
 * Rush's icon for its notifications: a one-colour runner for the status bar (Android draws it in one colour) and the
 * full icon beside the text. Both made from the extension's own icon.png and kept here, since an extension has no
 * Android resources of its own.
 */
internal object Look {
    private fun icon(png: String) = Base64.decode(png, Base64.DEFAULT).let { Icon.createWithBitmap(BitmapFactory.decodeByteArray(it, 0, it.size)) }

    fun small(): Icon = icon(SMALL)
    fun large(): Icon = icon(LARGE)

    private const val SMALL =
            "iVBORw0KGgoAAAANSUhEUgAAAGAAAABgCAYAAADimHc4AAAK50lEQVR42u1dfYxcVRX/nTfT7fJVCimlWEK6pNSiUhCDWFoaQwQs1WBEY/wAEg" +
            "kSICWhoiagxkTEyIdEIsFEPtJUhQYLMSTaIFahAg1FrEKkW7AF1hawLcUCtbs77/38Y8+tJy/ve96b2Zm+m0xm583c9+49597z8Tvn3AXqVre6" +
            "1a1udatbV5ocbBMmeWDOIsKaAZ0jegMARcQPfdeMul638ojfCH2eQnI6ySNC1z2SXr0DyiW+JyIBySMBfB7ApwGcDOBoAC0A2wE8DWC1iKy3fe" +
            "qlWwLx9f0rJF9henuY5JyoXVO3nPLeEZDkzYbA4yRbJH2Sgb58ve7rb3aQPMMysG4FZT7JbytRxwyBk9q4vv+b5JAysmZCQeKfaYgaMHsb0/fH" +
            "aga0J/fXKiFbzN9cn0/W+qAY8ecWWPlhURSQXN0JBvTTFnNzWQSgCcBv4z4C4EySU0XEt95zzYD0Nrck32imvir1l/qRAYMl3WcKgIFObdt+an" +
            "sAlAGy7QPwrlMxNQMy6GF9f15FRlGxEei9XgWwk6RUiZr2EwMcfvOk7gKv4MqlMu9RxYRqKyiT5hQhyYaIvAVgpRLRL0j8cQA/DzG2bll8AX0d" +
            "o7hOXmdsVN9/2HUnTF3xUl5dgiOWkNyfEZLwDRa0lmSTZKPTY7eE96pgZgfn0NT3c0m+GYIZxkMvC9StJnloJ8crCZOYVpICooi83ekgjPk8Hc" +
            "D1AC7DRCAmqv0DwK0icp9bMJ2KF0tEsHoagJ8BOEddemnTNAwAPAPgcgCvT+jLaiJOjnAkZwG4AsASAPsBrAPwuHrJCwEcA2AMwFa1mp4QkXFH" +
            "g64E643svJ3VtAerVGwGjDuV5GsRz3+J5AlZY8cdjwmb+OnTAE7X1dssyT4ngB0iMlRxqslUAH8B8AFd4Y6ovsIKWwB8BMCo8REIIOhWiooXwY" +
            "xHdbBTdQLtvhymsq7CldbUuZyvxG/pM90YBvTaPABni8i46qaWiPjdzA+yKzzQlXQTgMMBLNXBeyXogPUArtP7ByWLHU8JCpIXp3i/AYD5AH7X" +
            "Exkh6tSIcXDyvFw/qWhcDfP5CJKXkXzPBNzjIl2XWFN1UmcVlKng22WEG5e9D8kTSX4vQ9qJY8hukrOK2Pn67IZjflk0akZhKg5DKWv1tpP259" +
            "IKRaRlxrUIwNcAXATgMKNoGylI6V4Aow43yoIVGdPUT/M5+ja/Rz8PkvwCyT9GxHCzpJ243zynfkKqQRB6/kUkHyD5BMmVJM/rSv5QJzChkJiZ" +
            "TfJbJDeHREqrQODd6YEXSB4fpwtCyV2zSf465n4/6BgTylKoaYN1zyA5k+SdJN8KEbDVpkPoQLctJIfCTAit+s+R3B56tsWTSPLCdpS6FCDgUW" +
            "3ogj1JWIthzrEKHZxk5LuUGL9w+uIVAEtFZLMjoIi0FAe7RfVMnH5p6XgeE5HzNRbhV+nizyf5uFoSu3Vl5nntJrmR5MfidoLZ9mt0de1vI78n" +
            "qzjaTnKBGcPHSb5o9EaQolOGOyF6GiSfKmniW6MgX0P8pSFRUWVzTNhJcjHJ70SIqqS+PsmN7ViMzQzoYqBb8oOKr3gFvUjnFQ8BmCUiW3UXUA" +
            "dPkoMAbtffeiXGiuPu1dDvZ6i3bvs0M4ixAQAPm3u1So0Jq73sAXgHE4UMAzqwIphQU/v/HcB2R3Q3eLWnvw7g/SlEC3LGetOC8/b7IAPzabCm" +
            "DQDu0Ln4lShhg5K+D8CPFSnNq8DdBIcBXCciw+a+jgBzMJFSckhCWgnN9TEF+tLGsQnAaSlMzau8AWAVgOUi8p9OBnAQgfVkxoVi7Hwn+x9KCa" +
            "IHRmmuIHmb8Qfifvs2yWkkbyqYrm7vN270xSVRc5n0GFG4EC5C8SYR3yf5Lsl52ufZhD6O0KvMs75vfh8UUNQk+VuSJ5aFb3XVEzY7aFC93CAB" +
            "UnCr77va9+zQSo8zEc/T5wxov+tzMsE9978krw0H/fulmuWGFLPP2eLDJA/VPnck9HHE/yfJqWYBuEyJFRltfPfdBpIf7mYpa5VxhiEVK0nEcC" +
            "JgmfY9nORIiNhRq/ZHETCDY8JVpn8rRtYHqjum9M2qL6B43fWHjNi6MKO+ODXK4zZM+GpEH9deJHlOVvyqV4n/iRyKd8iAdA+GVmoUwzYkIbCG" +
            "CctIbgoFbH6ihd3QDLn+KmY3DPhlAiEtMW8wq/84knsTFLC71/I0sWHGISQXkFxIcma301Q6WUy3PmEHOJ2wWa0kJ4evTFC+jiHvkZydEfpuVB" +
            "E+nezp6W5ybxrXPwpuEADXish+c/3LCV64gwL+AGCHwsNBWqjUmMMN9Wj9yXCMTSdE0GdMcXTLyHyX0bxGfzegRPpQiv3udtIF2m9q38nvCsTQ" +
            "3THE3KLy3jPiZ1WKv+DSUX5PcqFVuDUjEtLVSV6tsMIuki+T/KlThsZaOUt3h58x/cQneZeL+9YV79mC70c5yCAE+jU0kpa12sWKqZ3q+Q70lS" +
            "dbZkFGxIlWjVBWwlUFI2SWWZtc8LyrQFqHIIZGiBGZZHBILLnVf6yKpiziJ04ktUKHMi3oS7EUgpkHtTIFRZShWf33tHHqSdiv8E2g/xaSM6pK" +
            "xeyaLNdczftIblP5+2eSl5M8LCsjDPHPSgi4hOGKVgGxNELyCmORNXod2TyZ5BsxE3+Z5DfcqjNy2ItJwM2reJlDTIXhj6cc8NaTCtow4E+hXJ" +
            "6o1fkGyRttqZBlhDE7r0whvoMqtpH8FMl1EdGwvPrhxp5jgiHcSSZPJq0OlyT3aFBlfkg0NVXx7s4YI/iS6X+x1oEx585phe/XM+LIyOvFKSHC" +
            "uO2/T3XG6eae96aYnY5Y68xBrG4hTNPY7zs5xdKBJKtuFJeXsQPmmDTxIts/IHm/ET1BSr9RkqeEFoE1f+fp/ZgxBuy+22ViAT3HhN8UyOcMcp" +
            "qYblfcGuPESSgEea4GZ5gRwniN5CE9xwCd+PEmqTVvDk6QIfXc7a4RFTVeQsTLsztDd9ZIhvqxtZ1WxF4J5UeBvv8LwGIANwPYif9X2fsZKiPF" +
            "pDCmHSXzTRHZqxX3jBuTYv0N/XwXgDUJR9i4+zzbs8f4hIC14/Tk2lcjFF07Wcy5a40NoPdkgnXUKqPYYjLWdU0neU1INPk55b4TT2NG8Xo5Pf" +
            "SjTaVNECP/R03FTG8jphHKcJDkpca7zaOA3W9uK7D6nR5YlGBd+SYVpb9iBhGM8Eh+1njNaQV3zo4fIXlkXjzfeNbXJPgW7toDfRu4iUIcNS/o" +
            "kZiSU2vvk+QXixDH7IBfZGDAir7LgMtSNa/5OL8yJ5eH293trEzdNS8kpDK6a0sOqtBlGA3VrIc7FTndpXHiq4uWyBoH8QSTaREk1BHM6CkHrM" +
            "Io2oAthS1KECN+lmUwPzd0i/jNSXDeZ2BWrIjIGCbKj9Bm7a0j5hkJxw+7a8+hjUK7nmZABCMOnNvWZuFzEGJA0up+BpPgwKZJcwJuSYf3BVr2" +
            "ekoCA5q6C/7arVNyvT7/F41zAcyOmasj9usAXqr6lPSDjQFuXqfp30kA3PMisk/LZmsGlNw+mkEBdxUB7VcGuBWfVFTurm3slvjpWwYYURL3P2" +
            "CoJucYgL/VDKguS29HTBGIOzhkG4ARZzXVDCh/Xvfo34GJzPnmsKWV6mvUqesVxqrjikAe0Soary7gqL4I5FKtnBnWU7+W2yrJmlqdi1V7cd/V" +
            "rUPQd56ahbpVuBvqVre61a1udet2+x+0KiSX4wMmiwAAAABJRU5ErkJggg=="

    private const val LARGE =
            "iVBORw0KGgoAAAANSUhEUgAAAIAAAACACAYAAADDPmHLAAAYW0lEQVR42u2deXBc5Znuf2fr03tL3uVdsg3YBhtbtmzkFRuwA2S4kJABMuSGhJ" +
            "rLkDAJU8mdVCU1a6aYSeVmAUJC4slNAjEMAcKEnbCEzXiTjWy8gG3JkiXZlrX3evb5o5fgRa3WarU5b1VXueR2q32+5/ve7XmfT5g//3IH1z6x" +
            "JrqPwAWAay4AXHMB4JoLANdcALjmAsA1FwCuuQBwzQWAay4AXHMB4JoLANdcALjmAsC1C9Fk9xEMzARByP3ZcRwXAJ+I41IUEQDbcbAsC8cBQQ" +
            "BJkhAFAQewbdsFwIW625PJJKZpoXpVAn4fkiRhWRbxRBItpSHJEj6vNwcSFwAXgEmiSDKl4Tg28+ZezPJllVw0ZxalpSV4PAq6btDR0cXhw3Vs" +
            "3V7Dvv0HEQQRn1fFKoLTQHA5gfmP/FgsTkX5DG7//M1ULl6IqqqYpplxAQ6CICBJErIso+saNbv28JtHn6CuvoFgMDDqXYILgLyLH2P1qmruuf" +
            "tOQqEA8XgC23YQhLODQMcBURQIBPxEo3EeeGgTb729hWAwOKpBIE2YMOmf3OU++9iPx+OsXlXN33/jnoz/TyGKIqIoIAhnv7I/T6U0FEVh9aor" +
            "aG45zqFDdXhVddRmCm4d4KydL5DUNMpnzuCeu+/EMAxM00SSpMLAI0mYpolhGHz1b+6konwGSU1DFAUXAMVijm1z++dvJhQKYBgGoij2230Yhk" +
            "EoFOD2z9+MM4pdgAuAM3Z/Ipli/rxLWFK5kHg8UfDOP9dJEI8nqKxcyLx5F5NIpkblKeAC4Ix83zItlldV4vF4sO3B+W3bdlA9HpZXVWKZ5mmB" +
            "owuAUWi27eBVVWbPLsc0rUEvmCAImKbFnNkVqKp30IByATDcu9+y8Pt9jB1TimVZDHbDCgJYlsWYMaX4/b7MZwouAEZt8OeAJEsoHmXI0jbHcf" +
            "AoCrIsMRozQRcAZ+5Y08LQjSHbqYIgoBtGxqW4ABjFu99JR+6JJO0dnUjS4Hes46SzgY6OThKJROYzHRcAozkN1DSNw4frM0e2M2hQybLEocN1" +
            "aKO0GOQC4MxTQJbYur0GTdcHvWBpQOls3V6DJMujshzsAuCMNNDv87Jv/4fU1NQSCPixLGtAn2VZFoGAn5pdtezf/yF+n5sGFk8wKAo88tvfEY" +
            "3GUBSl390827ZRFIVoNM4jv/0dgii6peBiOgV8qkpdfQMPPLQJRZFRFLngk8CyLGRFRlEUHnhoE3VHG/Cp6qjc/TAEjKDhLGycL59p2TahUIB3" +
            "3t2GJErc85U7iUTCRKMxbNvOtYDPxQcIhYJEo3F+8KOf5fgAo5kZNGBCiCCkd8tAumX9OUZFURjxAoogCDiOg6brdHf3UD5zOnd84VZWrViG1+" +
            "dF141eGUE7d9Xym0efoL6+8cJlBAmZXaKqHhYtvIyxY8cM+Rdrb+9gd+1eNE1HEkWcEWQCaZqGJEksXDCfWRUzSaU06uobMAyDJZWXc9mlcymJ" +
            "hPF4POiGQUdHJ4cO17Ptk8QJdByHT21cz5xZFWi6PqSuwHHSXbRDR+p48aXXRqx+LgoCKU1j6pTJfPXuLzNv7kUZyreIaVns2l3Lj+7/Oafa2h" +
            "kzpgRRFLHMDCtYSyHJctGxgvsNAEEQMAyDsrKJ3HjDtehDWDY9q4buUfj9f7/A8eMnURRlWGOCdOfOJBIJc993v8PUKWVEo7Gc+xEEiETCNDcf" +
            "5++//a90ZKqFkJkLyLiqYpsLcLOAjxVtUimNG2+4junTptLV1YMoikhS+iWKIh0dXUydUpYGvmGkYxRJxHEcLMsuusUfUBaQLm/KtLa20Xised" +
            "hdQGtrG/IwV9HSrWCbsWPHsLxqMYlEAlk+mwkkyxLJVIqFC+YTCgaxLDMTEfHJGg3L+rjXXn+LjvbOYQ0CHcfJjV0Nx8Jnj+62tnY2XLOOsrKJ" +
            "xGLxXjMb23YIBgL4fF56emLIsvjJmw10AEkS0XWDd7ZsG9Y0UJLEIU8DswuvGyapWIqA38ea1Sv4y8/e0GcrWJJE4vEEyWQKSRKKevEHVQhKD0" +
            "YKqKo6rIWgoXy+WaCmNA1DNxg3biwbrlrL+itXMWfOLCzTRDd6B0Da/Snsrt1LPJ4gEPAP2O+LopjjB5zP4FEu1mpdf3Z7trCTSCRwHCifOZ11" +
            "a1eycuVyyiZNwDRMEonEWRW+3pBfX9+IpmuEQv2f+hFFEdu2iccTufKyJEn4fN7c93SHQ4fwmDcMk2QqhVdVWbxoAVetX8OSxQsJh0IkUyl6or" +
            "HMe8WCPjOlpfjSF2+lo6OT7TW7iYTDBfcJJEkikUigKApVSxdRPnM6APVHG6ndsw/TsvCq6oieBhfkbKAoiui6TkrTGFNayvJllVy1bjWXXDwb" +
            "WZZJJlOYppUb5xoIx88wTb73/x5ky3s7iETygyAbaEajMebPu4Qv33FbpsgkZxpIJvsPfMSDP/0lTU3NaRCM0EkwrAAYqtSwP8eiIAjEEwmmTZ" +
            "nMlWtXsnrVFUydMhnLskilUumsYpBBq23bKLIMAnz/Bw/x5tvv9QoCSZLSvxe46S+u5Za/vBGv15tzR9kiUygUpLn5ON/6znfp7u4Z9tR3WIdD" +
            "swufHaMezMu27UzAJBT0ezVN4+bP3MC9X7+LpZWX41XVjLCDWfDnFEohF0WRVSuW0draxoGDH+HzeXOLlnUr0WiUqVMm84177+bT121ANwx0Xc" +
            "8NmmZPoUQiSdmkCTjA1u01qCM0UDosMUCW/142aeKfS7hC/3PNbNm59VRbDgj5Jnp7YjFuufl/8dd33k5PT5SenmjmQYvD4mayHcG/+/rf4FE9" +
            "vPDiq4TDoTQTWNfRdYONG9ZzxxduoSQSobunp9fvc2aRybatESkyycORuqmqh6vWr2Ha1ClD8pnHmpp59bU3e+07iKJAMpVizuwKbvncjXR39+" +
            "RYvsMdaNq2ja7r/O1X7sSjKPz3sy8hiiJjx47hy3fcxpVrVpBKacTi8T6/z/koMslD/UB0XWfZ0sXMrignGosPmgvvODC7opz2tg7e2bKt16PR" +
            "ceCvbv0sXq+XWCyOJIkjyh1IpTTuvusORFGkqbmFr959J2WTJhDtR5YhigKxeHxEi0zycByN48aNQdO0s5Q0BggBNE1j3Lgx5+TWSaJINBbjyj" +
            "UrWVZVSTw+cot/JggSiSRf+uKtmZ+J9PRECz6FLMvC5w1Ru2cf0Vgs4wbs4usG2rZNW1tHZqdmq3mDeYGqqrS1dZw1Zy8IAoZlEolEuO2WmzAL" +
            "nMC1bbvfmUVf7xcy/QrDMDEME13X+7X44XCIxmPNPP3M8yOqKCIPtf9XlHSpdOy4MUMWAxyuq2d37d6zOAGiKBCNJbn1czcxY8Y0urt78j707L" +
            "8NBPyYplkwl8EwTHw+b2a4Q8zbJOuPz8vqDUUiYQ4c+Igf3v8wHR2dxV8HyBInJ4wfN2RZwJn5e7Z/Xz5zOv/+b/+QWUinzza2LMvs2LkbWZa5" +
            "fOGlGVcl5FlSh/u+92Ouv/Yaqq9YSldX95AEl5Zl4fWmy7/P/OFFNj/+FLpu4PWObCVwWNLA7Azc8RMnB32UCYKALMuI4tkRsePA52/9LAG/j1" +
            "i89xZu9mRqa2vn/p9s4oN9B7nvu9/OHe3nAoBtO/h8KgcOfsTOmvc5cPAQ3/rmPVQtXdznSdN3g8shHA5x7FgzD2/6Ddt27CLgD4z44g8bALIL" +
            "pSjKEHYFnV4Dv3yL//Hy7aOPPcWbb21h/bpVzJt7EalUKi9oZFni3S3bc6fav/37j/jm332FlSuWDQgElmXj8Sh4PAov//FP/P9fP0ZnZxeRcB" +
            "jbPj+MInm4Z+2GI+LuT+Bn2zaBQIBtO2p4+52tlJZGWFZViSzL2HY63Trng5ElOju72VnzPnKGl2AYJv/x/QcwDJMr166guztaUMbhOA627RAK" +
            "BWhr7+CXv9rM62+8g6qqBIOBAY+ffSK7gf0N/ERRIpFM8uhjT2FZJmNKS6lctBBN633407ZtgoEAO3fV0txyHL/fj2XZyHJaG/j7P/wJhmFwzd" +
            "Vr+/z9tm0jSRLBoI8tW3fw802P0NJyglAomAGG7WoF90vDL5ni4jmzuP7aa4jF8qt4WZZFJBLmsf/6PYcP1yOKApddOpeysonE+3IbwLtbtp82" +
            "0mXbDqIoIcsCP7z/YXTD4Pprr6a7u+esPkN2cQOBAIlEgp8+/Cueff5lREkiHA6d111ftABI1xXSgZ/f78u7iI7j4PWqNDQe4/fPPI/P5yWlaV" +
            "RXV+VNSLJ09OMnTrJn775MYOac9veiKKKqHh58aBO6rvOZG68nFotjmmYuc1BkmVAoyJ49+/nZL37NR4eOEAoFc8DEVQsfgHZvPM6aVdUsq1rc" +
            "9w7O0Lc2P/40XT09eFWVyWWTWHDpXJJ5gj/bdlBVlZqaWto7OgmHgliWfdZnC4KA1+vl55seoaurm8/ceD3hUCiXWXT3RHny0Sf43VPPYllmn5" +
            "wBFwAF0tE3XL2WvijCHw/83np7K+FQiO7uHpZWXk5JSYSenlivwZsoCui6wZat25HE3mVisiDw+bw8/sQzvPveDhZcNo/SkgidXd3s2bufxmNN" +
            "BPx+FMU7Khe/aACQ7b9HQiEml03KS9zMBX6JJL/d/FSuY6eqKlcsX5pX/8920jqBdXVHOfjhkT7z8myWEwwGOHmylecam3LAUFVPrp5vu1KxI2" +
            "eWZREM+nnuhVf48NBh/H4fyWSK2bNmctGcirxaPY6d9v9bt9cQTxTeVMpS2EOhIJFImFAoOCBhCRcAfSh4dUejtBw/gaKcmy6V9d8NjU08/czz" +
            "+P0+HMfBMA2WL1uSEWvsfVFkWaK7u4dt23fh7aeyZzbqz7KYimVeQCwmlq9pmrz48hvIsnxaV+/P+XQ68n9085N0d/egyAqGYVJaUkL18iWkUl" +
            "qe3D9dLWw81kz90QYse2Ck0WKzorkwIp2eeag72oAoiiypvDxXAxBFEa9Xxefzsfnxp3n2uZcJ+H0IgkAsHuPT117NuitXkUwme43+s3FGaUmE" +
            "Sy6eQ0vLCZpbTiCK4rBPJrsA6E/UKsvs2r2How3HCAYDKLJMKpXi0OE6fvnrx3j2hVfwetUMO8lg4oTxfO2ev841kwrZ0TNnTmftmmpKSyLU1T" +
            "fQ3tGFx6OcsyFV7FaUcwFpFm0CSZKJREIAdHdHsSwTv9+fK9bEYjHu/du72LhhfYadU3hQJ4oiwUCAEydbefLpZ3nl1T+haTqBgP+s5pR7ApwH" +
            "U1UVSZLQNB0jM6vv8Xhyix9PJFh8+QK+9MXb8h79ee8JTKUIBPxUX7GUhZfNo72zk4aGJuyMO3IBMIRVvo9z5AvZXdn3ZGnWH9+VjuMgiSL3fu" +
            "0uxo8bi2EMTMUkO8eXSulMmjSBtatXMG3qFBobmzhxshVZkoZEUvYTC4DsoiQSSZLJFJqmY1oWiiynhaEG8GAlSSIai/EX12/k2o1XDZoh/OcZ" +
            "w7Qy2EVzKlizupqA38eRI0fp7o6iqp6ijQ/OGwCy83uCIFC5eAGrVy7nskvnEvD7aW1tIxaLoyjyOZU68tPSDSZOHM/X7/k/mZm8/IFfoYFhdn" +
            "I4penIskzlooVUVS1G03SO1NejawZqEbqF8xIEnluNS0YADNPg8JGjvPjya2zZsp2unh78Pl+uspZvl2WZQvd+7S42XrMub83/40DMkkoKdRPp" +
            "ef70JK/iUXi/9gM2P/40tXv3pX9WJFXA83ICZAs6paUl/Ms/fYs5s8uJxxNomp52AabFhPHjqL6iiqqli1FVDy3HT9DR0YUoiSiK3OtC5gK//3" +
            "1b7qLHPgdJ4wlKSiLYtp2bGC5EJDM7GqbrOlOnTmHtmhVMmjiejw4dIRqL4SmS2sGIA0AURRLJJF+47XNUL19CZ2c3siydFgSmKds6paURli+t" +
            "5IrlSwmHgpw4cZK29k7gbL5hfwI/27bxeb0cOlzHd/7xPpLJFHNmlxMOhdB1vd9uQdd1bNvmsvlzqVq6iPff30dHV1emZO0C4KyGi9ercvtf3Z" +
            "yr1Z/5sLMPNj3SrREMBlhSuZCV1csYN3YMp061c7L1VI7tK8sSPdFovwI/URJ54CebOHS4jr0fHGDHjt0EAj4qKmaiKEq641jgiHv2+yYSCcaP" +
            "H8fMGdP405vvFoWC2IgCICvHFgz6+fR1GzJ389m9PuRsBJ5OxTS8XpUFC+azZtUVTC6bRHtHJydPniKRSDJlSllBgZ9l2YRCQf742ps8/cwLhM" +
            "MhVI9CZ2cXb7+7lY8OHWFy2SSmTZ2MYzsYmbHywmVmU0yePImDHx6mobEJNVObcAGQCwDTi7B2TTWRSAjLsvvcZWcCQZZl5s27mLWrq6kon07r" +
            "qTZuuuE6FiyYRyKR6mM+QKarq4cf/PhnuTl9206TTTweDw2NTbz51hba2zuoKJ/BuLFj0Q0jL1BPdy/g83k52nCMPXv24/V6RzUARpwQImby9N" +
            "rafcyZVU4ikeo1sDsXELJTs9kIf/WqapZVVeaEl/Id/bbt4PP6+NVv/ovmluOn6ftkC0lZ5a8/PPcS723byU03XMfGDety5M6CXALFIx93XuoA" +
            "oihSf7SRpUsWMWHCOFKpVL8lZbLRuqZpFFrf9/t9HDj4EQ//4td5xszTP/P5fCSTSbZt38X7tR8wpqSE6dOmFKAAnuYuPP/iqzQ1t4z6bOC8AE" +
            "DOnAK7a/cxc8Y0pk2djCzJGKZZcAQ+EB0iURS5/8Ff0NRyvE/fnCWheL1eTp1q59U33iYeT7B0yaK8N4CKokgymeLJp58lkUgOu0hFURJCbMfB" +
            "6/XS3NzCt//hPu773o/Zu+8AXq9KIBAY8oGJdOAZ4PU/vc2u3XsI+AsTeEyLQKevkxUFISPe1Hvt37bTWUlr6yna2juKgkcgn7+7eWxUjwfbcX" +
            "jzrfd4772dLFx4Kdd9aj2LFy3A4/GQSCSxLHtQtfw0kUSmra2Dx594Bs8AroV1MhXIS+dfgijmu1DSQZElGhqbSCSSRXFjyHllBdsfY9U6jsPO" +
            "mvfZtbuW+fMu4VMb1rOsajHhcJBEIjlgXT/bdvD2Evj153uqqofp06dlroHvvUSMIHKk7mjRBIHy6Lipy84JNwB8sP8gez/Yz5zZFWy8Zh0rVy" +
            "yjJBIhkUxiGGbBQMgGfvv3H+TFl18jGOj/IGa2MllSEmFy2cS8Fcb0TIFG/dFjo973j0pSaJZD7/f5CAQC1NU38OMHf8E3v/XPPPHUH4jF40TC" +
            "IRRFwbLsgmRbbMfmkc1PZm4C7f9/Ny1SYTJl8iRKSiKYvQSAjuMgSxJdXT20HD/eK3PZBUA/gKCqKqFQkJbjJ/n5fz7CN/7vP/KrRx6nrb2DSD" +
            "iUqySeS07FNE1CoSBvvPFOvwK/c16QZVmUz5ye+X1OXhGKluMn6OrqHjGlzwt6MihbnPF4FLxeD51d3Tz62JO89MrrrF1dzTVXX8nMGdMxTZNU" +
            "SjvtgUciYY41tfDI5t+hqgMvxzqZU6CiYiY4du/+H5BkiaNHG9E0HdXjwXIBMHRAsKx0uTYSDpNIJHny98/xx9feYkV1FZ/asI7Zs8pRZAUnI7" +
            "6894MDPPjT/6Szswuv1zvgaDwbR8yYPhXDtHoVgRIy3/NIXQMI4FzoF0acPyBYSJJEJBxKD4q89Bpvvf0el86fS0X5OeTXB7H42VZv2aSJTBg/" +
            "Pn1JZt4CUJKGxqa0kDRuFjDsJ4IgCOnxbdtmR81utm7bCR+7gEGVpEHl4YIgYJgm06ZNIRQKkEicm12c1SA60XqKk62tGUaQCwBG6p5fgIDff9" +
            "YVLINXKEt/TkX5jExQl2d0XVFoamqhJxrD7/MVDSVMvnBu/baHRZFElmUqymdk6v+9v0+SROrqGzL8QtyLI7lARs3DoSBTp07GNMw8xJV02llX" +
            "35Chh7sA4EK4SdQw0rOF48aWYuSRo5MkiWg0zrGmZpQiyf9dABRQAjJMkxkzpuHL49NP6wC2dRTdJLELgHzCzw7MKp+RUwIvpAM4HLeTuAA4T5" +
            "1KVfUwc2YmAES4YDqALgAKFKUKBoNMmDAub2QviumLqoqpA+gCoB9uQMju8nzaRd09ebWLXABQfJXGQkSpsrI1x5pa6OjocAFwobmBvkWp0spi" +
            "L73yekZ9THDHwy+kU8Dj8eSKO3lFqV54Bb/PV5RB4AV5d/BQm6ZprKhexsZrrmRy2SQAWo6f4KVX3uCdLdvwqqorEnWhu4O+RKmK1WR3eQtzB9" +
            "l5hXg8AYCqehAEtWi6fi4AGJpuYzYOuFCk4lwAjIJ7kNw00DUXAK65AHDNBYBrLgBccwHgmgsA11wAuOYCwDUXAK65AHDNBYBrLgBccwHg2tDb" +
            "/wAZLWcMnDJ8yAAAAABJRU5ErkJggg=="
}
